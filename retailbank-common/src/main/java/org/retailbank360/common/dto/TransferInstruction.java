package org.retailbank360.common.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Instruction to debit one account and credit another.
 *
 * <p>Sent to account-service, which owns both balances and can therefore apply both legs inside a
 * single local database transaction. That is what makes the transfer genuinely ACID rather than a
 * best-effort pair of remote calls.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferInstruction {

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

    /** Business reference of the owning transaction record. */
    @NotNull(message = "Reference must not be null")
    private String reference;

    /** Correlates every step of the transfer saga, including its lock audit rows. */
    private String operationId;

    private String description;

    private String initiatedBy;

    @AssertTrue(message = "Source and destination accounts must be different")
    public boolean isDistinctAccounts() {
        return fromAccountId == null || !fromAccountId.equals(toAccountId);
    }
}
