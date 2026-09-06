package org.retailbank360.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result of a posted ledger movement.
 *
 * <p>Returned by the internal money API of account-service and consumed by the saga orchestrators.
 * The balances quoted here are the post-commit values, read inside the same transaction that wrote
 * them, so they are never a stale snapshot.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoneyMovementResponse {

    /** Business reference of the owning transaction or loan event. */
    private String reference;

    private String status;

    private Long accountId;

    private String maskedAccountNumber;

    private BigDecimal amount;

    private String currency;

    /** Balance of {@link #accountId} after the movement. */
    private BigDecimal balanceAfter;

    /** Ledger entry reference of the debit leg, when there is one. */
    private String debitEntryRef;

    /** Ledger entry reference of the credit leg, when there is one. */
    private String creditEntryRef;

    private Long counterpartyAccountId;

    private String counterpartyMaskedAccountNumber;

    /** Balance of the counterparty account after a transfer. */
    private BigDecimal counterpartyBalanceAfter;

    /**
     * Fencing token of the lock under which the movement was posted. Stamped on the ledger rows and
     * available to a caller that wants to prove ordering.
     */
    private Long fencingToken;

    private LocalDateTime postedAt;
}
