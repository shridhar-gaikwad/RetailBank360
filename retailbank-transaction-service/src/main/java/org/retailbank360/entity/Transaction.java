package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.retailbank360.constants.TransactionStatus;
import org.retailbank360.constants.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Customer-facing record of one money movement, and the state of the saga that performs it.
 *
 * <p>The original stub had no {@code @Id}, no column mapping and a {@code Double} amount. All three
 * are corrected here: an identity key, explicit columns, and {@link BigDecimal} money, because binary
 * floating point cannot represent currency exactly and would silently lose paise.</p>
 *
 * <p>This row is <em>not</em> the ledger. The ledger lives in account-service and is immutable; this
 * record is the workflow around it, and it does change state as the saga progresses.</p>
 */
@Entity
@Table(name = "transactions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_transactions_ref", columnNames = "transaction_ref"),
                @UniqueConstraint(name = "uk_transactions_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "ix_transactions_from", columnList = "from_account_id"),
                @Index(name = "ix_transactions_to", columnList = "to_account_id"),
                @Index(name = "ix_transactions_status", columnList = "status"),
                @Index(name = "ix_transactions_created", columnList = "created_at"),
                @Index(name = "ix_transactions_owner", columnList = "owner_customer_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business reference quoted to the customer and stamped on the ledger entries. */
    @Column(name = "transaction_ref", nullable = false, unique = true, length = 40)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.PENDING;

    /** Debited account. Null for a deposit. */
    @Column(name = "from_account_id")
    private Long fromAccountId;

    /** Credited account. Null for a withdrawal. */
    @Column(name = "to_account_id")
    private Long toAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "description", length = 255)
    private String description;

    /** Client-supplied key that makes a retry of this request safe. */
    @Column(name = "idempotency_key", unique = true, length = 120)
    private String idempotencyKey;

    /** Ledger entry references returned by account-service, for reconciliation. */
    @Column(name = "debit_entry_ref", length = 40)
    private String debitEntryRef;

    @Column(name = "credit_entry_ref", length = 40)
    private String creditEntryRef;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** Reference of the compensating reversal, when the saga had to roll the movement back. */
    @Column(name = "reversal_reference", length = 40)
    private String reversalReference;

    /**
     * Customer who owns the account this transaction acts on, resolved once from account-service.
     *
     * <p>Stored so a read can be authorised locally: a CUSTOMER principal may only see rows whose
     * owner is themselves, without a round trip to account-service on every list call.</p>
     */
    @Column(name = "owner_customer_id")
    private Long ownerCustomerId;

    @Column(name = "initiated_by", length = 120)
    private String initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void markSuccess(String debitEntryRef, String creditEntryRef, BigDecimal balanceAfter) {
        this.status = TransactionStatus.SUCCESS;
        this.debitEntryRef = debitEntryRef;
        this.creditEntryRef = creditEntryRef;
        this.balanceAfter = balanceAfter;
        this.failureReason = null;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = TransactionStatus.FAILED;
        this.failureReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.completedAt = LocalDateTime.now();
    }

    public void markReversed(String reversalReference, String reason) {
        this.status = TransactionStatus.REVERSED;
        this.reversalReference = reversalReference;
        this.failureReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.completedAt = LocalDateTime.now();
    }
}
