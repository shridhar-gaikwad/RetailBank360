package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an optimistic-locking version check fails, i.e. somebody else modified the row
 * between the read and the write. Maps to HTTP 409.
 */
public class ConcurrentUpdateException extends BusinessException {

    public ConcurrentUpdateException(String resource, Object id) {
        super(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                resource + " " + id + " was modified by another user. Reload the record and retry.");
    }

    public ConcurrentUpdateException(String message, Throwable cause) {
        super(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", message, cause);
    }
}
