package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a downstream microservice call fails or returns an unusable response. */
public class ExternalServiceException extends BusinessException {

    public ExternalServiceException(String service, String message) {
        super(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_SERVICE_ERROR",
                "Call to " + service + " failed: " + message);
    }

    public ExternalServiceException(String service, String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_SERVICE_ERROR",
                "Call to " + service + " failed: " + message, cause);
    }
}
