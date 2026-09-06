package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Take money out of an account. Refused if it would breach the minimum balance or overdraft limit. */
@Data
public class WithdrawRequest {

    @NotNull(message = "Account id must not be null")
    private Long accountId;

    @NotNull(message = "Amount must not be null")
    @DecimalMin(value = "0.01", message = "Withdrawal amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal amount;

    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    private String currency;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    private String idempotencyKey;
}
