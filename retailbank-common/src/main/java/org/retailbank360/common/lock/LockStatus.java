package org.retailbank360.common.lock;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Read-only view of one lock, returned by the lock status API.
 *
 * <p>This is what a UI polls to decide whether to grey out a button and show "this account is being
 * updated by another user" instead of letting the operator start a request that would come back 423.
 * The owner token is deliberately not exposed - only the node, the user and the operation are.</p>
 *
 * @param lockKey            canonical {@code type:id} key
 * @param resourceType       logical resource family
 * @param resourceId         identifier within that family
 * @param held               true when the lock is currently held and unexpired
 * @param orphaned           true when a holder is recorded but its TTL lapsed; recoverable
 * @param heldBy             node that holds it
 * @param heldByUser         user on whose behalf it is held
 * @param operationId        business operation being performed
 * @param acquiredAt         when it was taken
 * @param expiresAt          when it becomes stealable
 * @param secondsRemaining   TTL left, and therefore a sensible {@code Retry-After} for the caller
 * @param fencingToken       current fencing token of the resource
 * @param acquireCount       how many times this resource has ever been locked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LockStatus(
        String lockKey,
        String resourceType,
        String resourceId,
        boolean held,
        boolean orphaned,
        String heldBy,
        String heldByUser,
        String operationId,
        Instant acquiredAt,
        Instant expiresAt,
        long secondsRemaining,
        long fencingToken,
        long acquireCount) {

    public static LockStatus from(ResourceLock lock, Instant now) {
        boolean held = lock.isHeld(now);
        long remaining = held
                ? Math.max(0, java.time.Duration.between(now, lock.getExpiresAt()).toSeconds())
                : 0;
        return new LockStatus(
                lock.getLockKey(),
                lock.getResourceType(),
                lock.getResourceId(),
                held,
                lock.isOrphaned(now),
                lock.getOwnerNode(),
                lock.getOwnerUser(),
                lock.getOperationId(),
                lock.getAcquiredAt(),
                lock.getExpiresAt(),
                remaining,
                lock.getFencingToken(),
                lock.getAcquireCount());
    }

    /** State reported for a resource that has never been locked. */
    public static LockStatus free(String resourceType, String resourceId) {
        return new LockStatus(resourceType + ":" + resourceId, resourceType, resourceId,
                false, false, null, null, null, null, null, 0, 0, 0);
    }
}
