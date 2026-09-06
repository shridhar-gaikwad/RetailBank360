package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when the caller is authenticated but not allowed to touch this particular resource. */
public class UnauthorizedOperationException extends BusinessException {

    public UnauthorizedOperationException(String message) {
        super(HttpStatus.FORBIDDEN, "OPERATION_NOT_PERMITTED", message);
    }
}
