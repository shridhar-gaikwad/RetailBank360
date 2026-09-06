package org.retailbank360.common.retry;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.LockProperties;
import org.retailbank360.common.exception.ConcurrentUpdateException;
import org.retailbank360.common.exception.DeadlockDetectedException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Re-runs a unit of work that lost a race against another concurrent transaction.
 *
 * <p>Deadlocks cannot be designed away completely, only made rare and recoverable. The lock ordering
 * in the money services makes them rare; this template makes them recoverable. A retried operation
 * must be idempotent, which is why every money-moving API takes an idempotency key: replaying the
 * work either finds the committed result or starts a fresh transaction, never double-posts.</p>
 *
 * <p>Implemented directly rather than with spring-retry so no new dependency is introduced.</p>
 */
@Slf4j
public class ConcurrencyRetryTemplate {

    private final int maxAttempts;
    private final long baseDelayMillis;

    public ConcurrencyRetryTemplate(LockProperties properties) {
        this.maxAttempts = Math.max(1, properties.getDeadlockRetries());
        this.baseDelayMillis = Math.max(1, properties.getDeadlockRetryDelay().toMillis());
    }

    ConcurrencyRetryTemplate(int maxAttempts, long baseDelayMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMillis = Math.max(1, baseDelayMillis);
    }

    /**
     * Executes {@code work}, retrying only on transient concurrency failures.
     *
     * @param operation short description used in log lines and in the final error message
     * @throws DeadlockDetectedException  when the database kept reporting a deadlock or lock timeout
     * @throws ConcurrentUpdateException  when the optimistic version check kept failing
     */
    public <T> T execute(String operation, Supplier<T> work) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return work.get();
            } catch (PessimisticLockingFailureException e) {
                last = e;
                log.warn("{}: database lock conflict on attempt {}/{} - {}",
                        operation, attempt, maxAttempts, e.getMostSpecificCause().getMessage());
                backOff(attempt);
            } catch (ObjectOptimisticLockingFailureException e) {
                last = e;
                log.warn("{}: optimistic lock conflict on attempt {}/{} - {}",
                        operation, attempt, maxAttempts, e.getMessage());
                backOff(attempt);
            }
        }

        if (last instanceof ObjectOptimisticLockingFailureException) {
            throw new ConcurrentUpdateException(
                    operation + " failed after " + maxAttempts + " attempts because the record kept "
                            + "changing underneath it", last);
        }
        throw new DeadlockDetectedException(
                operation + " failed after " + maxAttempts + " attempts", last);
    }

    /** Void flavour for work that returns nothing. */
    public void executeVoid(String operation, Runnable work) {
        execute(operation, () -> {
            work.run();
            return null;
        });
    }

    /** Exponential back-off with jitter, so retrying threads do not collide again in lock-step. */
    private void backOff(int attempt) {
        long delay = baseDelayMillis * (1L << Math.min(attempt - 1, 5));
        long jitter = ThreadLocalRandom.current().nextLong(baseDelayMillis + 1);
        try {
            Thread.sleep(delay + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before a retry", e);
        }
    }
}
