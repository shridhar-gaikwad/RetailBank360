package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when creating an entity that violates a natural-key uniqueness rule. Maps to HTTP 409. */
public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String message) {
        super(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", message);
    }
}
