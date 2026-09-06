package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a distributed/resource lock could not be obtained inside the caller wait budget,
 * i.e. another user or process is currently performing a conflicting operation on the resource.
 *
 * <p>Maps to HTTP 423 LOCKED. The UI is expected to render a "resource in use" indicator and may
 * retry after {@link #getRetryAfterSeconds()} seconds; the operation is safe to retry because every
 * money-moving API is protected by an idempotency key.</p>
 */
public class LockAcquisitionException extends BusinessException {

    private final String lockKey;
    private final String heldBy;
    private final long retryAfterSeconds;

    public LockAcquisitionException(String lockKey, String heldBy, long retryAfterSeconds) {
        super(HttpStatus.LOCKED, "RESOURCE_LOCKED",
                "Resource '" + lockKey + "' is currently in use by another operation"
                        + (heldBy == null ? "" : " (" + heldBy + ")")
                        + ". Please retry in " + retryAfterSeconds + "s.");
        this.lockKey = lockKey;
        this.heldBy = heldBy;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getLockKey() {
        return lockKey;
    }

    public String getHeldBy() {
        return heldBy;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
