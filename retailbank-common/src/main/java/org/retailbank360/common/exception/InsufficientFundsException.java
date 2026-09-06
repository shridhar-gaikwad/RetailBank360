package org.retailbank360.common.exception;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/** Thrown when a debit would push an account past its available balance. Maps to HTTP 422. */
public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException(String accountRef, BigDecimal available, BigDecimal requested) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Insufficient funds on account " + accountRef
                        + ": available " + available + ", requested " + requested);
    }
}
