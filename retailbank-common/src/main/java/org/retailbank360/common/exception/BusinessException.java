package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for every expected (i.e. non-bug) failure raised by the domain layer.
 *
 * <p>Each subclass carries the HTTP status and a stable machine readable error code so that
 * {@code GlobalExceptionHandler} can translate it into a consistent API error payload without a
 * long chain of {@code instanceof} checks.</p>
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected BusinessException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    protected BusinessException(HttpStatus status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
