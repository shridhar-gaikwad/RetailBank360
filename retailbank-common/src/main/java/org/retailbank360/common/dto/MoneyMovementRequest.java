package org.retailbank360.common.dto;

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
 * Instruction to move money on a single account, sent by transaction-service or loan-service to the
 * internal ledger API of account-service.
 *
 * <p>Lives in the shared module so both ends of the call compile against one definition of the
 * contract.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyMovementRequest {

    @NotNull(message = "Account id must not be null")
    private Long accountId;

    @NotNull(message = "Amount must not be null")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal amount;

    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    private String currency;

    /** Ledger movement kind: {@code DEPOSIT}, {@code WITHDRAWAL}, {@code LOAN_DISBURSEMENT}, ... */
    @NotNull(message = "Movement type must not be null")
    private String movementType;

    /** Business reference of the owning transaction or loan event. */
    @NotNull(message = "Reference must not be null")
    private String reference;

    /** Correlates every step of one saga, including the lock audit rows. */
    private String operationId;

    private String description;

    /** Username on whose behalf the movement is posted, for the ledger and the audit trail. */
    private String initiatedBy;

    /**
     * When true the debit is allowed to breach the minimum balance rule. Reserved for internal
     * corrections such as a saga compensation, never for a customer-facing withdrawal.
     */
    private boolean bypassMinimumBalance;
}
