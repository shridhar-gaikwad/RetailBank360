package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.retailbank360.constants.TransactionStatus;
import org.retailbank360.constants.TransactionType;
import org.retailbank360.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction as returned by the API.
 *
 * <p>Also the payload stored against an idempotency key, so a replay of the same request is answered
 * with exactly this object rather than by repeating the work. That is why it carries the no-args
 * constructor Jackson needs to read it back.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResponse {

    private String transactionRef;

    private TransactionType transactionType;

    private TransactionStatus status;

    private Long fromAccountId;

    private Long toAccountId;

    private BigDecimal amount;

    private String currency;

    private String description;

    /** Balance of the account the customer acted on, after the movement. */
    private BigDecimal balanceAfter;

    private String debitEntryRef;

    private String creditEntryRef;

    private String failureReason;

    private String reversalReference;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public static TransactionResponse from(Transaction transaction) {
        return TransactionResponse.builder()
                .transactionRef(transaction.getTransactionRef())
                .transactionType(transaction.getTransactionType())
                .status(transaction.getStatus())
                .fromAccountId(transaction.getFromAccountId())
                .toAccountId(transaction.getToAccountId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .description(transaction.getDescription())
                .balanceAfter(transaction.getBalanceAfter())
                .debitEntryRef(transaction.getDebitEntryRef())
                .creditEntryRef(transaction.getCreditEntryRef())
                .failureReason(transaction.getFailureReason())
                .reversalReference(transaction.getReversalReference())
                .createdAt(transaction.getCreatedAt())
                .completedAt(transaction.getCompletedAt())
                .build();
    }
}
