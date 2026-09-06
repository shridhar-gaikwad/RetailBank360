package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Loan repayment.
 *
 * <p>The amount is collected from the loan's disbursement account and applied to the oldest
 * outstanding instalments first.</p>
 */
@Data
public class RepaymentRequest {

    @NotNull(message = "Amount must not be null")
    @DecimalMin(value = "0.01", message = "Repayment amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal amount;

    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    private String idempotencyKey;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;
}
