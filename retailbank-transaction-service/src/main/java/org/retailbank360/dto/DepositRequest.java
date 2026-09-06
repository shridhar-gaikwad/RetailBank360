package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Pay money into an account.
 *
 * <p>The amount is a {@link BigDecimal}, not a {@code Double}: binary floating point cannot represent
 * a value like 0.10 exactly, and rounding drift is not acceptable in a ledger.</p>
 */
@Data
public class DepositRequest {

    @NotNull(message = "Account id must not be null")
    private Long accountId;

    @NotNull(message = "Amount must not be null")
    @DecimalMin(value = "0.01", message = "Deposit amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal amount;

    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    private String currency;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    /**
     * Client-generated key. Resubmitting the same key returns the original result instead of
     * depositing twice; it may also be supplied as the {@code Idempotency-Key} header.
     */
    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    private String idempotencyKey;
}
