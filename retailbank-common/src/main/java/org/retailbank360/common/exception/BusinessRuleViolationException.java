package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request is syntactically valid but breaks a banking rule
 * (minimum balance, daily transfer limit, KYC not verified, account closed, ...). Maps to HTTP 422.
 */
public class BusinessRuleViolationException extends BusinessException {

    public BusinessRuleViolationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessRuleViolationException(String errorCode, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }
}
