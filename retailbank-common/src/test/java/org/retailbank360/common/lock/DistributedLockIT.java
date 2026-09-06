package org.retailbank360.common.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.config.LockProperties;
import org.retailbank360.common.exception.LockAcquisitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the distributed lock.
 *
 * <p>Two lock managers are built by hand over the <em>same</em> repositories with different node ids.
 * That is a faithful stand-in for two instances of a service pointed at one database: mutual
 * exclusion, TTL takeover and fencing all have to work between them, and none of it can rely on
 * in-process state.</p>
 */
@SpringBootTest
class DistributedLockIT {

    @Autowired
    private ResourceLockRepository lockRepository;

    @Autowired
    private LockAuditEventRepository auditRepository;

    @Autowired
    private LockAuditService auditService;

    @Autowired
    private LockProperties lockProperties;

    @Autowired
    @Qualifier("retailBankRequiresNewTransactionTemplate")
    private TransactionTemplate requiresNew;

    private DistributedLockManager nodeA;
    private DistributedLockManager nodeB;

    @BeforeEach
    void setUp() {
        lockRepository.deleteAll();
        // Audit rows refuse individual deletion by design, so the retention purge is the only way
        // to clear them - which is itself worth asserting.
        auditService.purgeOlderThan(Instant.now().plusSeconds(3600));
        nodeA = newManager("node-A");
        nodeB = newManager("node-B");
    }

    private DistributedLockManager newManager(String nodeId) {
        return new DatabaseDistributedLockManager(lockRepository, auditService, lockProperties,
                requiresNew, nodeId);
    }

    @Test
    @DisplayName("A lock held by one instance cannot be taken by another")
    void lockIsExclusiveAcrossInstances() {
        LockHandle held = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 1L, "op-1",
                Duration.ofSeconds(30), Duration.ofMillis(200)));

        assertThat(held).isNotNull();
        assertThat(nodeB.tryAcquire(LockRequest.of(LockResourceTypes.ACCOUNT, 1L, "op-2",
                Duration.ofSeconds(30), Duration.ofMillis(200)))).isEmpty();

        nodeA.release(held);

        Optional<LockHandle> afterRelease = nodeB.tryAcquire(
                LockRequest.of(LockResourceTypes.ACCOUNT, 1L, "op-3",
                        Duration.ofSeconds(30), Duration.ofMillis(200)));
        assertThat(afterRelease).isPresent();
        nodeB.release(afterRelease.get());
    }

    @Test
    @DisplayName("Waiting past the budget fails with 423 and a Retry-After hint")
    void waitingCallerGetsLockedResponse() {
        LockHandle held = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 2L, "op-1",
                Duration.ofSeconds(30), Duration.ofMillis(100)));

        assertThatThrownBy(() -> nodeB.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 2L, "op-2",
                Duration.ofSeconds(30), Duration.ofMillis(200))))
                .isInstanceOf(LockAcquisitionException.class)
                .satisfies(thrown -> {
                    LockAcquisitionException lockFailure = (LockAcquisitionException) thrown;
                    assertThat(lockFailure.getStatus().value()).isEqualTo(423);
                    assertThat(lockFailure.getRetryAfterSeconds()).isPositive();
                    assertThat(lockFailure.getLockKey()).isEqualTo("ACCOUNT:2");
                });

        nodeA.release(held);
    }

    @Test
    @DisplayName("Orphaned lock cleanup: a crashed holder's lock is taken over once its TTL lapses")
    void orphanedLockIsRecoveredAfterTtl() throws InterruptedException {
        // Node A takes the lock and then "crashes": release is never called.
        LockHandle abandoned = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 3L, "crashing-op",
                Duration.ofMillis(300), Duration.ofMillis(200)));
        assertThat(abandoned).isNotNull();

        // While the TTL is still valid, nobody else may have it.
        assertThat(nodeB.tryAcquire(LockRequest.of(LockResourceTypes.ACCOUNT, 3L, "op-2",
                Duration.ofSeconds(5), Duration.ofMillis(50)))).isEmpty();

        Thread.sleep(400);

        ResourceLock row = lockRepository.findByLockKey("ACCOUNT:3").orElseThrow();
        assertThat(row.isOrphaned(Instant.now()))
                .as("the lock is now an orphan: an owner is recorded but the TTL has lapsed")
                .isTrue();

        // The next caller simply takes it over. No operator action, no restart.
        Optional<LockHandle> takenOver = nodeB.tryAcquire(
                LockRequest.of(LockResourceTypes.ACCOUNT, 3L, "recovery-op",
                        Duration.ofSeconds(5), Duration.ofMillis(200)));
        assertThat(takenOver).isPresent();
        assertThat(takenOver.get().ownerNode()).isEqualTo("node-B");
        assertThat(takenOver.get().fencingToken())
                .as("taking over bumps the fencing token, so the crashed holder is fenced out")
                .isGreaterThan(abandoned.fencingToken());

        assertThat(auditRepository.findAll())
                .anySatisfy(event -> assertThat(event.getAction()).isEqualTo(LockAuditEvent.Action.STOLEN));

        nodeB.release(takenOver.get());
    }

    @Test
    @DisplayName("Orphaned lock cleanup: the reaper clears a crashed holder nobody is contending for")
    void reaperClearsOrphanNobodyIsWaitingFor() throws InterruptedException {
        nodeA.acquire(LockRequest.of(LockResourceTypes.LOAN, 9L, "crashing-op",
                Duration.ofMillis(200), Duration.ofMillis(200)));

        Thread.sleep(300);

        assertThat(nodeB.reapOrphanedLocks())
                .as("the background sweep reclaims the abandoned lock")
                .isEqualTo(1);

        ResourceLock row = lockRepository.findByLockKey("LOAN:9").orElseThrow();
        assertThat(row.getOwnerToken()).isNull();
        assertThat(row.getReleasedAt()).isNotNull();

        assertThat(auditRepository.findAll())
                .anySatisfy(event -> assertThat(event.getAction()).isEqualTo(LockAuditEvent.Action.REAPED));

        // A second sweep finds nothing left to do, so the operation is idempotent.
        assertThat(nodeB.reapOrphanedLocks()).isZero();
    }

    @Test
    @DisplayName("Exactly one of many competing instances wins the same lock")
    void onlyOneInstanceWinsUnderContention() throws InterruptedException {
        int contenders = 12;
        List<DistributedLockManager> managers = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            managers.add(newManager("node-" + i));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(contenders);
        AtomicInteger winners = new AtomicInteger();
        List<LockHandle> acquired = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(contenders);

        try {
            for (DistributedLockManager manager : managers) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        manager.tryAcquire(LockRequest.of(LockResourceTypes.ACCOUNT, 4L, "race",
                                        Duration.ofSeconds(10), Duration.ZERO))
                                .ifPresent(handle -> {
                                    winners.incrementAndGet();
                                    acquired.add(handle);
                                });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get())
                .as("mutual exclusion: a single conditional UPDATE means only one competitor can win")
                .isEqualTo(1);
        assertThat(acquired).hasSize(1);
    }

    @Test
    @DisplayName("A thread may re-enter a lock it already holds")
    void lockIsReentrantWithinAThread() {
        LockHandle outer = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 5L, "outer",
                Duration.ofSeconds(10), Duration.ofMillis(200)));
        LockHandle inner = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 5L, "inner",
                Duration.ofSeconds(10), Duration.ofMillis(200)));

        assertThat(inner.reentrant()).isTrue();
        assertThat(inner.ownerToken()).isEqualTo(outer.ownerToken());

        // Releasing the inner hold must not free the lock for anybody else.
        nodeA.release(inner);
        assertThat(nodeB.tryAcquire(LockRequest.of(LockResourceTypes.ACCOUNT, 5L, "other",
                Duration.ofSeconds(10), Duration.ZERO))).isEmpty();

        nodeA.release(outer);
        Optional<LockHandle> afterOuterRelease = nodeB.tryAcquire(
                LockRequest.of(LockResourceTypes.ACCOUNT, 5L, "other",
                        Duration.ofSeconds(10), Duration.ofMillis(200)));
        assertThat(afterOuterRelease).isPresent();
        nodeB.release(afterOuterRelease.get());
    }

    @Test
    @DisplayName("The fencing token increases on every acquisition of a resource")
    void fencingTokenIsMonotonic() {
        long previous = 0;
        for (int i = 0; i < 5; i++) {
            LockHandle handle = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 6L, "op-" + i,
                    Duration.ofSeconds(10), Duration.ofMillis(200)));
            assertThat(handle.fencingToken()).isGreaterThan(previous);
            previous = handle.fencingToken();
            nodeA.release(handle);
        }
        assertThat(lockRepository.findByLockKey("ACCOUNT:6").orElseThrow().getAcquireCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Every acquisition and release is recorded with the user and the operation id")
    void lifecycleEventsAreAudited() {
        LockHandle handle = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 7L, "audited-op",
                Duration.ofSeconds(10), Duration.ofMillis(200)));
        nodeA.release(handle);

        List<LockAuditEvent> events = auditRepository.findAll().stream()
                .filter(event -> "ACCOUNT:7".equals(event.getLockKey()))
                .toList();

        assertThat(events).extracting(LockAuditEvent::getAction)
                .containsExactlyInAnyOrder(LockAuditEvent.Action.ACQUIRED, LockAuditEvent.Action.RELEASED);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getOperationId()).isEqualTo("audited-op");
            assertThat(event.getOwnerUser()).isNotBlank();
            assertThat(event.getEventAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("An administrator can force-release a stuck lock, and the fencing token protects the loser")
    void forceReleaseIsRecordedAndFences() {
        LockHandle held = nodeA.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 8L, "stuck-op",
                Duration.ofMinutes(10), Duration.ofMillis(200)));

        assertThat(nodeB.forceRelease("ACCOUNT:8", "operator intervention")).isTrue();

        LockHandle newHolder = nodeB.acquire(LockRequest.of(LockResourceTypes.ACCOUNT, 8L, "next-op",
                Duration.ofSeconds(10), Duration.ofMillis(200)));
        assertThat(newHolder.fencingToken()).isGreaterThan(held.fencingToken());

        nodeB.release(newHolder);
        assertThat(nodeB.forceRelease("ACCOUNT:8", "already free")).isFalse();
    }
}
