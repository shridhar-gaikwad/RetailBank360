package org.retailbank360.common.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.exception.ConcurrentUpdateException;
import org.retailbank360.common.exception.DeadlockDetectedException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the bounded retry that makes a lost concurrency race recoverable. */
class ConcurrencyRetryTemplateTest {

    private final ConcurrencyRetryTemplate retryTemplate = new ConcurrencyRetryTemplate(3, 1);

    @Test
    @DisplayName("Work that succeeds first time is not retried")
    void doesNotRetryOnSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        String result = retryTemplate.execute("transfer", () -> {
            attempts.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("A deadlock is retried and can still succeed")
    void retriesADeadlockAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = retryTemplate.execute("transfer", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DeadlockLoserDataAccessException("deadlock detected", null);
            }
            return "settled";
        });

        assertThat(result).isEqualTo("settled");
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("A persistent deadlock surfaces as a clear 409, not a raw database error")
    void surfacesAPersistentDeadlock() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute("transfer", () -> {
            attempts.incrementAndGet();
            throw new DeadlockLoserDataAccessException("deadlock detected", null);
        }))
                .isInstanceOf(DeadlockDetectedException.class)
                .hasMessageContaining("Retry the request with the same idempotency key");

        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("A persistent optimistic-lock clash surfaces as a reload-and-retry conflict")
    void surfacesAPersistentOptimisticLockClash() {
        assertThatThrownBy(() -> retryTemplate.execute("update account", () -> {
            throw new ObjectOptimisticLockingFailureException("Account", 1L);
        }))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessageContaining("kept changing underneath it");
    }

    @Test
    @DisplayName("A business failure is not retried")
    void doesNotRetryBusinessFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute("transfer", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("insufficient funds");
        })).isInstanceOf(IllegalStateException.class);

        // Retrying a rejected request would only waste time and confuse the caller.
        assertThat(attempts).hasValue(1);
    }
}
