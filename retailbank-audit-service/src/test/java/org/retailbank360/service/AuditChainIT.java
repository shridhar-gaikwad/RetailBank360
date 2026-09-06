package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.dto.AuditEventResponse;
import org.retailbank360.dto.ChainVerificationResponse;
import org.retailbank360.entity.AuditEvent;
import org.retailbank360.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail and its tamper evidence.
 *
 * <p>Storing events is easy; being able to prove none of them has been edited is the part that
 * matters to an auditor, and that is what the hash chain and {@link AuditEventService#verifyChain()}
 * provide.</p>
 */
@SpringBootTest
class AuditChainIT {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // Audit rows refuse deletion through JPA - that is the guarantee - so the reset goes around it.
        jdbcTemplate.execute("delete from audit_events");
        jdbcTemplate.execute("delete from resource_lock_audit");
        jdbcTemplate.execute("delete from resource_locks");
    }

    @Test
    @DisplayName("An event is stored with its context and linked into the chain")
    void recordsAnEvent() {
        AuditEventResponse stored = auditEventService.record(event("TRANSFER-1", AuditActions.TRANSFER_COMPLETED));

        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getAction()).isEqualTo(AuditActions.TRANSFER_COMPLETED);
        assertThat(stored.getSourceService()).isEqualTo("retailbank-account-service");
        assertThat(stored.getEventHash()).hasSize(64);
        assertThat(stored.getPreviousHash()).as("the first event has nothing before it").isNull();
        assertThat(stored.getDetails()).contains("amount");
    }

    @Test
    @DisplayName("Each event links to the one before it")
    void chainsEventsTogether() {
        auditEventService.record(event("OP-1", AuditActions.ACCOUNT_OPENED));
        AuditEventResponse second = auditEventService.record(event("OP-2", AuditActions.ACCOUNT_CREDITED));
        AuditEventResponse third = auditEventService.record(event("OP-3", AuditActions.TRANSFER_COMPLETED));

        List<AuditEvent> stored = auditEventRepository.findAllByOrderByIdAsc();
        assertThat(stored).hasSize(3);
        assertThat(second.getPreviousHash()).isEqualTo(stored.get(0).getEventHash());
        assertThat(third.getPreviousHash()).isEqualTo(second.getEventHash());
    }

    @Test
    @DisplayName("A complete chain verifies as intact")
    void verifiesAnIntactChain() {
        for (int i = 0; i < 5; i++) {
            auditEventService.record(event("OP-" + i, AuditActions.TRANSFER_COMPLETED));
        }

        ChainVerificationResponse verification = auditEventService.verifyChain();

        assertThat(verification.isIntact()).isTrue();
        assertThat(verification.getEventsChecked()).isEqualTo(5);
        assertThat(verification.getShardsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("Each service gets its own chain, so one broken shard does not implicate the others")
    void chainsAreShardedPerService() {
        auditEventService.record(event("OP-1", AuditActions.TRANSFER_COMPLETED));
        AuditEventResponse fromAccounts = auditEventService.record(event("OP-2", AuditActions.ACCOUNT_OPENED));
        auditEventService.record(fromService("retailbank-loan-service", "OP-3", AuditActions.LOAN_DISBURSED));
        auditEventService.record(fromService("retailbank-loan-service", "OP-4", AuditActions.LOAN_CLOSED));

        // Two independent chains: the first loan event starts a fresh one rather than continuing
        // whatever account-service last wrote.
        ChainVerificationResponse intact = auditEventService.verifyChain();
        assertThat(intact.isIntact()).isTrue();
        assertThat(intact.getShardsChecked()).isEqualTo(2);
        assertThat(intact.getEventsChecked()).isEqualTo(4);

        jdbcTemplate.update("update audit_events set actor_username = ? where id = ?",
                "tampered", fromAccounts.getId());

        ChainVerificationResponse broken = auditEventService.verifyChain();
        assertThat(broken.isIntact()).isFalse();
        assertThat(broken.getShards())
                .filteredOn(shard -> "retailbank-account-service".equals(shard.getSourceService()))
                .singleElement()
                .satisfies(shard -> assertThat(shard.isIntact()).isFalse());
        assertThat(broken.getShards())
                .filteredOn(shard -> "retailbank-loan-service".equals(shard.getSourceService()))
                .singleElement()
                .satisfies(shard -> assertThat(shard.isIntact())
                        .as("an unrelated service's trail is unaffected")
                        .isTrue());
    }

    @Test
    @DisplayName("Editing a historical row is detected, and the exact row is named")
    void detectsATamperedRow() {
        auditEventService.record(event("OP-1", AuditActions.TRANSFER_COMPLETED));
        AuditEventResponse target = auditEventService.record(event("OP-2", AuditActions.TRANSFER_COMPLETED));
        auditEventService.record(event("OP-3", AuditActions.TRANSFER_COMPLETED));

        // Simulate someone editing the database directly to hide what an operator did.
        jdbcTemplate.update("update audit_events set actor_username = ? where id = ?",
                "somebody-else", target.getId());

        ChainVerificationResponse verification = auditEventService.verifyChain();

        assertThat(verification.isIntact()).isFalse();
        assertThat(verification.getShards())
                .singleElement()
                .satisfies(shard -> {
                    assertThat(shard.isIntact()).isFalse();
                    assertThat(shard.getFirstBrokenEventId()).isEqualTo(target.getId());
                    assertThat(shard.getProblem()).contains("altered since it was recorded");
                });
    }

    @Test
    @DisplayName("Removing a historical row is detected")
    void detectsADeletedRow() {
        auditEventService.record(event("OP-1", AuditActions.TRANSFER_COMPLETED));
        AuditEventResponse removed = auditEventService.record(event("OP-2", AuditActions.TRANSFER_COMPLETED));
        auditEventService.record(event("OP-3", AuditActions.TRANSFER_COMPLETED));

        jdbcTemplate.update("delete from audit_events where id = ?", removed.getId());

        ChainVerificationResponse verification = auditEventService.verifyChain();

        assertThat(verification.isIntact()).isFalse();
        assertThat(verification.getShards())
                .anySatisfy(shard -> assertThat(shard.getProblem())
                        .contains("does not point at the previous event hash"));
    }

    @Test
    @DisplayName("Events are immutable through the application, not merely by convention")
    void refusesToModifyAnEventThroughJpa() {
        auditEventService.record(event("OP-1", AuditActions.TRANSFER_COMPLETED));
        AuditEvent stored = auditEventRepository.findAllByOrderByIdAsc().get(0);

        stored.setOutcome("FAILURE");
        assertThatThrownBy(() -> auditEventRepository.saveAndFlush(stored))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("Concurrent writers still produce a single valid chain")
    void keepsTheChainValidUnderConcurrency() throws InterruptedException {
        int writers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(writers);

        try {
            for (int i = 0; i < writers; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        auditEventService.record(event("CONCURRENT-" + index, AuditActions.TRANSFER_COMPLETED));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Appending under the shared resource lock is what keeps this true: without it two writers
        // would read the same tail and both claim the same predecessor.
        ChainVerificationResponse verification = auditEventService.verifyChain();
        assertThat(verification.isIntact()).isTrue();
        assertThat(verification.getEventsChecked()).isEqualTo(writers);
    }

    @Test
    @DisplayName("Events can be pulled together by the operation they belong to")
    void findsEveryEventOfOneOperation() {
        auditEventService.record(event("SAGA-1", AuditActions.TRANSACTION_INITIATED));
        auditEventService.record(event("SAGA-1", AuditActions.TRANSFER_COMPLETED));
        auditEventService.record(event("SAGA-2", AuditActions.TRANSFER_COMPLETED));

        assertThat(auditEventService.byOperation("SAGA-1")).hasSize(2);
        assertThat(auditEventService.byOperation("SAGA-2")).hasSize(1);
    }

    @Test
    @DisplayName("The audit view filters, and exports as CSV")
    void searchesAndExports() {
        auditEventService.record(event("OP-1", AuditActions.TRANSFER_COMPLETED));
        auditEventService.record(event("OP-2", AuditActions.LOGIN_FAILED));

        assertThat(auditEventService.search(AuditActions.TRANSFER_COMPLETED, null, null, null, null,
                null, null, 0, 50).getContent()).hasSize(1);
        assertThat(auditEventService.search(null, "ACCOUNT", null, null, null, null, null, 0, 50)
                .getContent()).hasSize(2);

        String csv = auditEventService.toCsv(
                auditEventService.search(null, null, null, null, null, null, null, 0, 50).getContent());
        assertThat(csv).startsWith("Id,Occurred At,Source,Action")
                .contains(AuditActions.TRANSFER_COMPLETED)
                .contains(AuditActions.LOGIN_FAILED);
    }

    private AuditEventRequest event(String operationId, String action) {
        return fromService("retailbank-account-service", operationId, action);
    }

    private AuditEventRequest fromService(String sourceService, String operationId, String action) {
        return AuditEventRequest.builder()
                .sourceService(sourceService)
                .action(action)
                .entityType("ACCOUNT")
                .entityId("42")
                .actorUsername("teller1")
                .actorRole("TELLER")
                .operationId(operationId)
                .correlationId("corr-" + operationId)
                .outcome(AuditActions.OUTCOME_SUCCESS)
                .details(Map.of("amount", "100.00"))
                .build();
    }
}
