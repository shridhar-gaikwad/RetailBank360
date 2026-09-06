package org.retailbank360.common.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.IdempotencyConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the idempotency store.
 *
 * <p>This is the guarantee that makes retrying a money-moving request safe, and therefore the
 * guarantee that lets the lock template retry after a deadlock.</p>
 */
@SpringBootTest
class IdempotencyServiceIT {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Replaying a key returns the stored result instead of doing the work again")
    void replaysTheStoredResult() {
        AtomicInteger executions = new AtomicInteger();
        Map<String, Object> request = Map.of("accountId", 1, "amount", "100.00");

        TestResult first = idempotencyService.execute("TRANSFER", "key-1", request, TestResult.class,
                () -> new TestResult("TXN-1", executions.incrementAndGet()));
        TestResult replay = idempotencyService.execute("TRANSFER", "key-1", request, TestResult.class,
                () -> new TestResult("TXN-2", executions.incrementAndGet()));

        assertThat(executions).as("the work ran exactly once").hasValue(1);
        assertThat(replay.reference()).isEqualTo(first.reference()).isEqualTo("TXN-1");
    }

    @Test
    @DisplayName("Reusing a key with a different payload is refused")
    void refusesKeyReuseWithADifferentPayload() {
        idempotencyService.execute("TRANSFER", "key-2", Map.of("amount", "100.00"), TestResult.class,
                () -> new TestResult("TXN-1", 1));

        assertThatThrownBy(() -> idempotencyService.execute("TRANSFER", "key-2",
                Map.of("amount", "999.00"), TestResult.class, () -> new TestResult("TXN-2", 2)))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request payload");
    }

    @Test
    @DisplayName("A rejected request does not burn the key, so a corrected retry can proceed")
    void allowsRetryAfterABusinessFailure() {
        Map<String, Object> request = Map.of("amount", "100.00");

        assertThatThrownBy(() -> idempotencyService.execute("TRANSFER", "key-3", request,
                TestResult.class, () -> {
                    throw new BusinessRuleViolationException("Insufficient funds");
                })).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(repository.findByScopeAndIdempotencyKey("TRANSFER", "key-3"))
                .isPresent()
                .get()
                .extracting(IdempotencyRecord::getStatus)
                .isEqualTo(IdempotencyRecord.Status.FAILED);

        TestResult afterRetry = idempotencyService.execute("TRANSFER", "key-3", request,
                TestResult.class, () -> new TestResult("TXN-9", 1));
        assertThat(afterRetry.reference()).isEqualTo("TXN-9");
    }

    @Test
    @DisplayName("The same key in a different scope is a different operation")
    void scopesKeysIndependently() {
        AtomicInteger executions = new AtomicInteger();

        idempotencyService.execute("TRANSFER", "shared-key", Map.of("a", 1), TestResult.class,
                () -> new TestResult("TXN-1", executions.incrementAndGet()));
        idempotencyService.execute("LOAN_REPAYMENT", "shared-key", Map.of("a", 1), TestResult.class,
                () -> new TestResult("REPAY-1", executions.incrementAndGet()));

        assertThat(executions).hasValue(2);
    }

    @Test
    @DisplayName("A blank key means the caller opted out and the work simply runs")
    void runsWithoutAKey() {
        AtomicInteger executions = new AtomicInteger();

        idempotencyService.execute("TRANSFER", null, Map.of("a", 1), TestResult.class,
                () -> new TestResult("TXN-1", executions.incrementAndGet()));
        idempotencyService.execute("TRANSFER", "  ", Map.of("a", 1), TestResult.class,
                () -> new TestResult("TXN-2", executions.incrementAndGet()));

        assertThat(executions).hasValue(2);
        assertThat(repository.count()).isZero();
    }

    /** Stored and replayed as JSON, so it needs to be a plain deserialisable shape. */
    record TestResult(String reference, int executionNumber) {
    }
}
