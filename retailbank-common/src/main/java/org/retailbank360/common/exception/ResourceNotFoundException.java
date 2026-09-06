package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a requested entity does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public ResourceNotFoundException(String resource, Object identifier) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + " not found with id: " + identifier);
    }
}
