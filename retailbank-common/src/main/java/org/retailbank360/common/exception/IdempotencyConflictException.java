package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an idempotency key is replayed with a <em>different</em> request payload, or while the
 * original request holding that key is still in flight.
 */
public class IdempotencyConflictException extends BusinessException {

    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }
}
