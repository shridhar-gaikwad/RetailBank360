package org.retailbank360.common.lock;

import org.retailbank360.common.exception.LockAcquisitionException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Resource-level mutual exclusion that holds across threads, processes and service instances.
 *
 * <p>The shipped implementation is {@link DatabaseDistributedLockManager}, which stores lock state in
 * a table and therefore inherits the database's own atomicity guarantees and needs no extra
 * infrastructure. The interface exists so a deployment that already runs Redis or ZooKeeper can drop
 * in a Redlock or an ephemeral-znode implementation without any change to the banking services -
 * they only ever talk to {@link LockTemplate}.</p>
 *
 * <p>Locks are advisory and cooperative: they coordinate <em>application</em> critical sections such
 * as a transfer, a disbursement or a settlement batch. They complement, and never replace, the
 * database transaction and the {@code PESSIMISTIC_WRITE} row locks taken inside it.</p>
 */
public interface DistributedLockManager {

    /**
     * Acquires the lock, waiting up to the request's wait budget.
     *
     * @throws LockAcquisitionException (HTTP 423) when the resource stays busy for the whole budget
     */
    LockHandle acquire(LockRequest request);

    /** Non-throwing variant: empty when the resource is busy. */
    Optional<LockHandle> tryAcquire(LockRequest request);

    /**
     * Releases the lock. Safe to call more than once and safe to call on a lock that has already
     * been taken over: it never throws, so it can live in a {@code finally} block.
     */
    void release(LockHandle handle);

    /** Pushes out the TTL of a still-owned lock. Returns the refreshed handle, or empty if lost. */
    Optional<LockHandle> renew(LockHandle handle, Duration extension);

    /** Current state of one key, for the "resource in use" indicator in the UI. */
    Optional<LockStatus> status(String lockKey);

    /** Everything currently held on this cluster; optionally narrowed to one resource type. */
    List<LockStatus> heldLocks(String resourceType);

    /**
     * Reclaims locks whose owner died without releasing. Called on a schedule by {@code LockReaper}
     * and exposed so an administrator, or a test, can force a sweep.
     *
     * @return number of orphaned locks reclaimed
     */
    int reapOrphanedLocks();

    /**
     * Break-glass release of a lock regardless of who owns it.
     *
     * <p>Reserved for administrators. The previous owner keeps its now-stale handle, which is exactly
     * why every acquisition bumps the fencing token: a write arriving from the displaced holder can
     * still be identified and rejected.</p>
     *
     * @return true when a held lock was actually released
     */
    boolean forceRelease(String lockKey, String reason);

    /** Identifier of this JVM inside the lock registry. */
    String nodeId();
}
