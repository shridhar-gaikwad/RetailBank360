package org.retailbank360.common.lock;

import java.time.Duration;
import java.time.Instant;

/**
 * Proof that this thread currently owns a resource lock, and the token needed to release it.
 *
 * @param lockKey      canonical {@code type:id} key
 * @param resourceType logical resource family, e.g. {@code ACCOUNT}
 * @param resourceId   identifier within that family
 * @param ownerToken   secret owner id; a release only succeeds when it still matches the stored value
 * @param ownerNode    id of the JVM that acquired the lock
 * @param operationId  business operation this lock protects
 * @param acquiredAt   when the lock was taken
 * @param expiresAt    TTL boundary after which other callers may take it over
 * @param fencingToken strictly increasing per resource; stamp it on writes so a delayed writer that
 *                     lost its lock can be rejected by comparing tokens
 * @param reentrant    true when this handle is a nested acquisition and must not release the lock
 */
public record LockHandle(
        String lockKey,
        String resourceType,
        String resourceId,
        String ownerToken,
        String ownerNode,
        String operationId,
        Instant acquiredAt,
        Instant expiresAt,
        long fencingToken,
        boolean reentrant) {

    /** True when the TTL has already elapsed, i.e. this handle can no longer be trusted. */
    public boolean isExpired() {
        return expiresAt == null || !expiresAt.isAfter(Instant.now());
    }

    public Duration remainingTtl() {
        return expiresAt == null ? Duration.ZERO : Duration.between(Instant.now(), expiresAt);
    }

    /** Copy of this handle marked as a nested acquisition. */
    public LockHandle asReentrant() {
        return new LockHandle(lockKey, resourceType, resourceId, ownerToken, ownerNode, operationId,
                acquiredAt, expiresAt, fencingToken, true);
    }

    /** Copy of this handle with a pushed-out expiry, after a successful renewal. */
    public LockHandle withExpiry(Instant newExpiry) {
        return new LockHandle(lockKey, resourceType, resourceId, ownerToken, ownerNode, operationId,
                acquiredAt, newExpiry, fencingToken, reentrant);
    }
}
