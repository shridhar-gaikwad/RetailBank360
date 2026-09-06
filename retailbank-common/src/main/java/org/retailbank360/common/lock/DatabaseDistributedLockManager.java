package org.retailbank360.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.LockProperties;
import org.retailbank360.common.exception.LockAcquisitionException;
import org.retailbank360.common.security.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Distributed lock backed by the {@code resource_locks} table.
 *
 * <h2>Why a table</h2>
 * The database is already the single point of truth every instance of a service talks to, and it
 * already provides atomic conditional updates. A one-row compare-and-set therefore gives the same
 * mutual exclusion Redlock or a ZooKeeper znode would, without adding a second piece of
 * infrastructure that itself has to be deployed, secured and kept available.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Mutual exclusion across nodes</b> - acquisition is a single conditional {@code UPDATE};
 *       exactly one competitor can see one affected row.</li>
 *   <li><b>No permanent orphans</b> - every lock carries a TTL. If the owning JVM dies mid-operation
 *       the next caller simply takes the lock over once the TTL lapses, and the background reaper
 *       clears it even when nobody is contending.</li>
 *   <li><b>Fencing</b> - each acquisition bumps a per-resource counter. A writer stamps that token on
 *       what it writes, so a paused process that wakes up after losing its lock can be rejected by
 *       comparing tokens rather than corrupting a balance.</li>
 *   <li><b>Re-entrancy</b> - a thread that already holds a key can take it again (a transfer locks
 *       two accounts, a disbursement locks a loan and an account), and only the outermost release
 *       actually frees it.</li>
 *   <li><b>Bounded waiting</b> - callers wait at most their budget and then get HTTP 423 with a
 *       {@code Retry-After}, instead of blocking a request thread indefinitely.</li>
 * </ul>
 *
 * <p>Lock bookkeeping runs in its own {@code REQUIRES_NEW} transaction so that taking a lock is
 * visible to other nodes immediately, and so that releasing it is not undone when the business
 * transaction it protects rolls back.</p>
 */
@Slf4j
public class DatabaseDistributedLockManager implements DistributedLockManager {

    private final ResourceLockRepository lockRepository;
    private final LockAuditService auditService;
    private final LockProperties properties;
    private final TransactionTemplate requiresNew;
    private final String nodeId;

    /** Locks held by the current thread, so nested acquisitions do not self-deadlock. */
    private final ThreadLocal<Map<String, Reentrant>> heldByThread = ThreadLocal.withInitial(HashMap::new);

    public DatabaseDistributedLockManager(ResourceLockRepository lockRepository,
                                          LockAuditService auditService,
                                          LockProperties properties,
                                          TransactionTemplate requiresNew,
                                          String nodeId) {
        this.lockRepository = lockRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.requiresNew = requiresNew;
        this.nodeId = nodeId;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public LockHandle acquire(LockRequest request) {
        Duration wait = request.waitTimeout() != null ? request.waitTimeout() : properties.getDefaultWait();
        return doAcquire(request, wait)
                .orElseThrow(() -> {
                    LockStatus current = status(request.lockKey()).orElse(null);
                    String heldBy = current == null ? null
                            : current.heldBy() + (current.heldByUser() == null ? "" : "/" + current.heldByUser());
                    long retryAfter = current == null ? 1 : Math.max(1, current.secondsRemaining());
                    auditService.record(LockAuditEvent.of(request.lockKey(), LockAuditEvent.Action.WAIT_TIMEOUT,
                            null, nodeId, SecurityUtils.currentUsername(), request.operationId(), null,
                            "Gave up after waiting " + wait.toMillis() + "ms"));
                    return new LockAcquisitionException(request.lockKey(), heldBy, retryAfter);
                });
    }

    @Override
    public Optional<LockHandle> tryAcquire(LockRequest request) {
        Duration wait = request.waitTimeout() != null ? request.waitTimeout() : properties.getDefaultWait();
        return doAcquire(request, wait);
    }

    private Optional<LockHandle> doAcquire(LockRequest request, Duration wait) {
        String key = request.lockKey();

        Reentrant existing = heldByThread.get().get(key);
        if (existing != null) {
            existing.depth++;
            log.debug("Re-entered lock {} (depth {})", key, existing.depth);
            auditService.record(LockAuditEvent.of(key, LockAuditEvent.Action.REENTERED,
                    existing.handle.ownerToken(), nodeId, SecurityUtils.currentUsername(),
                    request.operationId(), existing.handle.fencingToken(), "depth " + existing.depth));
            return Optional.of(existing.handle.asReentrant());
        }

        Duration ttl = request.ttl() != null ? request.ttl() : properties.getDefaultTtl();
        String ownerToken = nodeId + ":" + UUID.randomUUID();
        String ownerUser = SecurityUtils.currentUsername();

        long deadlineNanos = System.nanoTime() + Math.max(0, wait.toNanos());
        long delayMillis = Math.max(1, properties.getRetryDelay().toMillis());
        long maxDelayMillis = Math.max(delayMillis, properties.getMaxRetryDelay().toMillis());

        while (true) {
            Attempt attempt = attemptAcquire(request, ownerToken, ownerUser, ttl);
            if (attempt.handle() != null) {
                heldByThread.get().put(key, new Reentrant(attempt.handle()));
                log.debug("Acquired lock {} as {} (fencing token {})", key, ownerToken, attempt.handle().fencingToken());
                auditService.record(LockAuditEvent.of(key, attempt.action(), ownerToken, nodeId, ownerUser,
                        request.operationId(), attempt.handle().fencingToken(), attempt.detail()));
                return Optional.of(attempt.handle());
            }

            if (System.nanoTime() >= deadlineNanos) {
                log.debug("Lock {} still busy after the {}ms wait budget", key, wait.toMillis());
                return Optional.empty();
            }

            sleepBeforeRetry(Math.min(delayMillis, Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000)));
            delayMillis = Math.min(delayMillis * 2, maxDelayMillis);
        }
    }

    /**
     * One acquisition round.
     *
     * <p>Step one is the compare-and-set update, which covers both "free" and "the previous holder
     * timed out". Step two only runs the very first time a resource is ever locked, when there is no
     * row yet; a unique-constraint violation there simply means another instance created it first,
     * and the caller loops.</p>
     */
    private Attempt attemptAcquire(LockRequest request, String ownerToken, String ownerUser, Duration ttl) {
        Attempt result = requiresNew.execute(status -> compareAndSet(request, ownerToken, ownerUser, ttl));
        if (result == null || result.handle() != null || !result.rowMissing()) {
            return result == null ? Attempt.busy(null, false) : result;
        }

        try {
            return requiresNew.execute(status -> insertOwned(request, ownerToken, ownerUser, ttl));
        } catch (DataIntegrityViolationException e) {
            log.debug("Lost the race to create lock row {}; retrying", request.lockKey());
            return Attempt.busy("another instance created the lock row first", false);
        }
    }

    private Attempt compareAndSet(LockRequest request, String ownerToken, String ownerUser, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String key = request.lockKey();

        Optional<ResourceLock> before = lockRepository.findByLockKey(key);
        boolean takingOverOrphan = before.map(lock -> lock.isOrphaned(now)).orElse(false);
        String previousOwner = before.map(ResourceLock::getOwnerNode).orElse(null);

        int updated = lockRepository.acquireIfFree(key, ownerToken, nodeId, ownerUser,
                request.operationId(), now, expiresAt);

        if (updated == 1) {
            ResourceLock stored = lockRepository.findByLockKey(key).orElseThrow(
                    () -> new IllegalStateException("Lock row " + key + " vanished right after acquisition"));
            LockHandle handle = toHandle(stored, request.operationId());
            if (takingOverOrphan) {
                log.warn("Reclaimed orphaned lock {} previously held by node {}", key, previousOwner);
                return new Attempt(handle, LockAuditEvent.Action.STOLEN,
                        "TTL of previous holder " + previousOwner + " had lapsed", false);
            }
            return new Attempt(handle, LockAuditEvent.Action.ACQUIRED, null, false);
        }

        if (before.isEmpty()) {
            return Attempt.busy(null, true);
        }
        return Attempt.busy("held by " + before.get().getOwnerNode(), false);
    }

    private Attempt insertOwned(LockRequest request, String ownerToken, String ownerUser, Duration ttl) {
        Instant now = Instant.now();
        ResourceLock fresh = ResourceLock.forKey(request.resourceType(), request.resourceId());
        fresh.setOwnerToken(ownerToken);
        fresh.setOwnerNode(nodeId);
        fresh.setOwnerUser(ownerUser);
        fresh.setOperationId(request.operationId());
        fresh.setAcquiredAt(now);
        fresh.setExpiresAt(now.plus(ttl));
        fresh.setFencingToken(1L);
        fresh.setAcquireCount(1L);
        ResourceLock saved = lockRepository.saveAndFlush(fresh);
        return new Attempt(toHandle(saved, request.operationId()), LockAuditEvent.Action.ACQUIRED,
                "first acquisition of this resource", false);
    }

    @Override
    public void release(LockHandle handle) {
        if (handle == null) {
            return;
        }
        String key = handle.lockKey();
        Map<String, Reentrant> held = heldByThread.get();

        Reentrant entry = held.get(key);
        if (entry != null && entry.depth > 1) {
            entry.depth--;
            log.debug("Released nested hold on {} (depth now {})", key, entry.depth);
            return;
        }
        held.remove(key);
        if (held.isEmpty()) {
            heldByThread.remove();
        }

        try {
            Integer released = requiresNew.execute(
                    status -> lockRepository.releaseIfOwner(key, handle.ownerToken(), Instant.now()));
            long heldMillis = handle.acquiredAt() == null ? 0
                    : Duration.between(handle.acquiredAt(), Instant.now()).toMillis();

            if (released != null && released == 1) {
                log.debug("Released lock {} after {}ms", key, heldMillis);
                auditService.record(withHeldMillis(LockAuditEvent.of(key, LockAuditEvent.Action.RELEASED,
                        handle.ownerToken(), nodeId, SecurityUtils.currentUsername(), handle.operationId(),
                        handle.fencingToken(), null), heldMillis));
            } else {
                // The TTL lapsed while we were working and somebody else took over. The fencing token
                // is what prevents that other holder's work from being corrupted by ours.
                log.warn("Lock {} was no longer owned by {} at release time (TTL lapsed after {}ms)",
                        key, handle.ownerToken(), heldMillis);
                auditService.record(withHeldMillis(LockAuditEvent.of(key, LockAuditEvent.Action.LOST,
                        handle.ownerToken(), nodeId, SecurityUtils.currentUsername(), handle.operationId(),
                        handle.fencingToken(), "Lock had already been taken over"), heldMillis));
            }
        } catch (RuntimeException e) {
            // Never propagate: release() is always called from a finally block, and masking the real
            // business exception with a bookkeeping failure would be far worse than a stale lock,
            // which the TTL and the reaper clean up anyway.
            log.error("Failed to release lock {}; it will expire at {}", key, handle.expiresAt(), e);
        }
    }

    @Override
    public Optional<LockHandle> renew(LockHandle handle, Duration extension) {
        Instant now = Instant.now();
        Instant newExpiry = now.plus(extension == null ? properties.getDefaultTtl() : extension);
        Integer renewed = requiresNew.execute(
                status -> lockRepository.renewIfOwner(handle.lockKey(), handle.ownerToken(), now, newExpiry));

        if (renewed == null || renewed != 1) {
            log.warn("Could not renew lock {}: it is no longer owned by this node", handle.lockKey());
            return Optional.empty();
        }
        auditService.record(LockAuditEvent.of(handle.lockKey(), LockAuditEvent.Action.RENEWED,
                handle.ownerToken(), nodeId, SecurityUtils.currentUsername(), handle.operationId(),
                handle.fencingToken(), "extended to " + newExpiry));

        LockHandle renewedHandle = handle.withExpiry(newExpiry);
        Reentrant entry = heldByThread.get().get(handle.lockKey());
        if (entry != null) {
            entry.handle = renewedHandle;
        }
        return Optional.of(renewedHandle);
    }

    @Override
    public Optional<LockStatus> status(String lockKey) {
        Instant now = Instant.now();
        return lockRepository.findByLockKey(lockKey).map(lock -> LockStatus.from(lock, now));
    }

    @Override
    public List<LockStatus> heldLocks(String resourceType) {
        Instant now = Instant.now();
        List<ResourceLock> rows = (resourceType == null || resourceType.isBlank())
                ? lockRepository.findHeld()
                : lockRepository.findHeldByType(resourceType);
        List<LockStatus> statuses = new ArrayList<>(rows.size());
        for (ResourceLock row : rows) {
            statuses.add(LockStatus.from(row, now));
        }
        return statuses;
    }

    @Override
    public int reapOrphanedLocks() {
        Instant cutoff = Instant.now().minus(properties.getReaperGracePeriod());
        List<ResourceLock> orphans = lockRepository.findOrphaned(cutoff);
        if (orphans.isEmpty()) {
            return 0;
        }

        int reclaimed = 0;
        for (ResourceLock orphan : orphans) {
            String ownerToken = orphan.getOwnerToken();
            Integer released = requiresNew.execute(
                    status -> lockRepository.releaseIfOwner(orphan.getLockKey(), ownerToken, Instant.now()));
            if (released != null && released == 1) {
                reclaimed++;
                log.warn("Reaped orphaned lock {} held by node {} for operation {} since {}",
                        orphan.getLockKey(), orphan.getOwnerNode(), orphan.getOperationId(), orphan.getAcquiredAt());
                auditService.record(LockAuditEvent.of(orphan.getLockKey(), LockAuditEvent.Action.REAPED,
                        ownerToken, orphan.getOwnerNode(), orphan.getOwnerUser(), orphan.getOperationId(),
                        orphan.getFencingToken(),
                        "Owner did not release before the TTL at " + orphan.getExpiresAt()));
            }
        }
        return reclaimed;
    }

    @Override
    public boolean forceRelease(String lockKey, String reason) {
        Optional<ResourceLock> current = lockRepository.findByLockKey(lockKey);
        if (current.isEmpty() || current.get().getOwnerToken() == null) {
            return false;
        }
        ResourceLock lock = current.get();
        Integer released = requiresNew.execute(
                status -> lockRepository.releaseIfOwner(lockKey, lock.getOwnerToken(), Instant.now()));

        boolean success = released != null && released == 1;
        if (success) {
            auditService.record(LockAuditEvent.of(lockKey, LockAuditEvent.Action.REAPED,
                    lock.getOwnerToken(), lock.getOwnerNode(), SecurityUtils.currentUsername(),
                    lock.getOperationId(), lock.getFencingToken(), "Force released: " + reason));
        }
        return success;
    }

    private LockHandle toHandle(ResourceLock lock, String operationId) {
        return new LockHandle(lock.getLockKey(), lock.getResourceType(), lock.getResourceId(),
                lock.getOwnerToken(), lock.getOwnerNode(), operationId, lock.getAcquiredAt(),
                lock.getExpiresAt(), lock.getFencingToken(), false);
    }

    private static LockAuditEvent withHeldMillis(LockAuditEvent event, long heldMillis) {
        event.setHeldMillis(heldMillis);
        return event;
    }

    /** Randomised back-off so competing threads do not retry in lock-step. */
    private void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis + ThreadLocalRandom.current().nextLong(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a resource lock", e);
        }
    }

    /** Per-thread hold with a nesting depth, so only the outermost release frees the lock. */
    private static final class Reentrant {
        private LockHandle handle;
        private int depth = 1;

        private Reentrant(LockHandle handle) {
            this.handle = handle;
        }
    }

    /** Result of a single acquisition round. */
    private record Attempt(LockHandle handle, LockAuditEvent.Action action, String detail, boolean rowMissing) {

        static Attempt busy(String detail, boolean rowMissing) {
            return new Attempt(null, null, detail, rowMissing);
        }
    }
}
