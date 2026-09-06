package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the database reported a deadlock (or a lock wait timeout) and the operation could not
 * be completed even after the configured retries. Maps to HTTP 409 so the client knows the request
 * is safe to replay with the same idempotency key.
 */
public class DeadlockDetectedException extends BusinessException {

    public DeadlockDetectedException(String message, Throwable cause) {
        super(HttpStatus.CONFLICT, "DEADLOCK_DETECTED",
                "The operation conflicted with another concurrent operation and was rolled back: "
                        + message + ". Retry the request with the same idempotency key.", cause);
    }
}
