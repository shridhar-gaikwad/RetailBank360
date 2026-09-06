package org.retailbank360.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.retry.ConcurrencyRetryTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The API the banking services actually use. Wraps a critical section in a resource lock and makes
 * sure the lock is always released, whatever happens inside.
 *
 * <p>Two things are deliberately handled here rather than at each call site:</p>
 * <ul>
 *   <li><b>Lock ordering.</b> {@link #executeWithLocks} sorts the keys before acquiring, so a
 *       transfer from A to B and a simultaneous transfer from B to A can never take the two locks in
 *       opposite orders. That is the cheapest deadlock <em>prevention</em> available, and it applies
 *       to the application-level locks and, by extension, to the row locks taken inside.</li>
 *   <li><b>Retry on a lost race.</b> Optionally re-runs the section when the database reports a
 *       deadlock or an optimistic-lock clash, with exponential back-off and jitter.</li>
 * </ul>
 *
 * <p>The lock is taken <em>outside</em> the business transaction on purpose: holding a database
 * transaction open while waiting for a lock would pin a connection and turn contention into
 * connection-pool exhaustion.</p>
 */
@Slf4j
public class LockTemplate {

    private final DistributedLockManager lockManager;
    private final ConcurrencyRetryTemplate retryTemplate;

    public LockTemplate(DistributedLockManager lockManager, ConcurrencyRetryTemplate retryTemplate) {
        this.lockManager = lockManager;
        this.retryTemplate = retryTemplate;
    }

    /** Runs {@code work} while holding one lock, handing it the fencing token of the acquisition. */
    public <T> T executeWithLock(LockRequest request, Function<LockHandle, T> work) {
        LockHandle handle = lockManager.acquire(request);
        try {
            return work.apply(handle);
        } finally {
            lockManager.release(handle);
        }
    }

    /** Convenience overload for work that does not need the handle. */
    public <T> T executeWithLock(LockRequest request, Supplier<T> work) {
        return executeWithLock(request, handle -> work.get());
    }

    public void executeWithLock(LockRequest request, Runnable work) {
        executeWithLock(request, handle -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} while holding several locks at once, e.g. both sides of a transfer.
     *
     * <p>Keys are de-duplicated and acquired in a stable, sorted order. If any acquisition fails the
     * already-held locks are released in reverse order before the failure propagates.</p>
     */
    public <T> T executeWithLocks(List<LockRequest> requests, Function<List<LockHandle>, T> work) {
        List<LockRequest> ordered = new ArrayList<>(new LinkedHashSet<>(requests));
        ordered.sort(Comparator.comparing(LockRequest::lockKey));

        List<LockHandle> handles = new ArrayList<>(ordered.size());
        try {
            for (LockRequest request : ordered) {
                handles.add(lockManager.acquire(request));
            }
            return work.apply(handles);
        } finally {
            for (int i = handles.size() - 1; i >= 0; i--) {
                lockManager.release(handles.get(i));
            }
        }
    }

    /**
     * Same as {@link #executeWithLocks}, but re-runs the whole section when the database reports a
     * transient concurrency failure. Use it for money movement, where the work is idempotency-key
     * protected and therefore safe to replay.
     */
    public <T> T executeWithLocksAndRetry(String operation, List<LockRequest> requests,
                                          Function<List<LockHandle>, T> work) {
        return retryTemplate.execute(operation, () -> executeWithLocks(requests, work));
    }

    /** Single-lock flavour of {@link #executeWithLocksAndRetry}. */
    public <T> T executeWithLockAndRetry(String operation, LockRequest request, Function<LockHandle, T> work) {
        return retryTemplate.execute(operation, () -> executeWithLock(request, work));
    }

    /**
     * Attempts the work only if the resource is free right now, without queueing.
     *
     * @return empty when the resource is busy, so the caller can answer "in use" immediately
     */
    public <T> java.util.Optional<T> executeIfFree(LockRequest request, Function<LockHandle, T> work) {
        java.util.Optional<LockHandle> handle = lockManager.tryAcquire(request.noWait());
        if (handle.isEmpty()) {
            log.debug("Skipping work on {}: resource is busy", request.lockKey());
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(work.apply(handle.get()));
        } finally {
            lockManager.release(handle.get());
        }
    }

    /** Renews a lock held by long running work so its TTL does not lapse mid-operation. */
    public LockHandle renew(LockHandle handle, Duration extension) {
        return lockManager.renew(handle, extension).orElse(handle);
    }

    /** Distinct keys currently held, for diagnostics. */
    public Set<String> heldKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (LockStatus status : lockManager.heldLocks(null)) {
            keys.add(status.lockKey());
        }
        return keys;
    }

    public DistributedLockManager lockManager() {
        return lockManager;
    }
}
