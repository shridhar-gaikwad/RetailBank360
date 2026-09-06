package org.retailbank360.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Move money between two accounts.
 *
 * <p>Both legs are applied by account-service inside one database transaction, so the debit and the
 * credit either both happen or neither does.</p>
 */
@Data
public class TransferRequest {

    @NotNull(message = "Source account id must not be null")
    private Long fromAccountId;

    @NotNull(message = "Destination account id must not be null")
    private Long toAccountId;

    @NotNull(message = "Amount must not be null")
    @DecimalMin(value = "0.01", message = "Transfer amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal amount;

    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    private String currency;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    private String idempotencyKey;

    @AssertTrue(message = "Source and destination accounts must be different")
    public boolean isDistinctAccounts() {
        return fromAccountId == null || !fromAccountId.equals(toAccountId);
    }
}
