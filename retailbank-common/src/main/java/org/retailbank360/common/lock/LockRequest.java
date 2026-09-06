package org.retailbank360.common.lock;

import java.time.Duration;

/**
 * What to lock, for whom and for how long.
 *
 * @param resourceType logical family, e.g. {@code ACCOUNT}, {@code LOAN}, {@code SETTLEMENT_BATCH}
 * @param resourceId   identifier within that family; together they form the row-level lock key
 * @param operationId  business operation id (transfer reference, idempotency key, batch id)
 * @param ttl          how long the lock survives without renewal, i.e. the crash-recovery window
 * @param waitTimeout  how long to keep trying before giving up with HTTP 423
 */
public record LockRequest(String resourceType, String resourceId, String operationId,
                          Duration ttl, Duration waitTimeout) {

    public LockRequest {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType is required to build a lock key");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required to build a lock key");
        }
    }

    public String lockKey() {
        return resourceType + ":" + resourceId;
    }

    /** Uses the configured default TTL and wait budget. */
    public static LockRequest of(String resourceType, Object resourceId, String operationId) {
        return new LockRequest(resourceType, String.valueOf(resourceId), operationId, null, null);
    }

    public static LockRequest of(String resourceType, Object resourceId, String operationId,
                                 Duration ttl, Duration waitTimeout) {
        return new LockRequest(resourceType, String.valueOf(resourceId), operationId, ttl, waitTimeout);
    }

    /** Fail immediately instead of queueing, for endpoints that prefer a fast "in use" answer. */
    public LockRequest noWait() {
        return new LockRequest(resourceType, resourceId, operationId, ttl, Duration.ZERO);
    }
}
