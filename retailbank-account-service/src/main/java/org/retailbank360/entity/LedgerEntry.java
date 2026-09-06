package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.retailbank360.constants.LedgerDirection;
import org.retailbank360.constants.MovementType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One immutable line in the transaction ledger.
 *
 * <p>The ledger is append-only: entries are never updated and never deleted. A mistaken posting is
 * corrected by writing a compensating {@link MovementType#REVERSAL} entry that references the
 * original, so the history of an account is always reconstructible and always adds up. The lifecycle
 * callbacks below turn that from a convention into something the persistence layer enforces.</p>
 *
 * <p>Each entry records the balance <em>after</em> it was applied. Since entries are written inside
 * the same transaction and under the same row lock as the balance update itself, that snapshot can
 * never disagree with the account, and a statement needs no recomputation to be trustworthy.</p>
 */
@Entity
@Table(name = "ledger_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_entry_ref", columnNames = "entry_ref"),
        indexes = {
                @Index(name = "ix_ledger_account_time", columnList = "account_id, posted_at"),
                @Index(name = "ix_ledger_reference", columnList = "reference"),
                @Index(name = "ix_ledger_operation", columnList = "operation_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique reference of this individual line, e.g. {@code LEDG-20260903-1A2B3C4D}. */
    @Column(name = "entry_ref", nullable = false, unique = true, length = 40)
    private String entryRef;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "account_number", nullable = false, length = 30)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private LedgerDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    /** Always positive. The sign of the movement is carried by {@link #direction}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Balance of the account immediately after this entry was applied. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    /** The other side of a transfer, when there is one. */
    @Column(name = "counterparty_account_id")
    private Long counterpartyAccountId;

    /** Business reference of the owning transaction or loan event. */
    @Column(name = "reference", nullable = false, length = 60)
    private String reference;

    /** Correlates every entry produced by one saga, including its lock audit rows. */
    @Column(name = "operation_id", length = 120)
    private String operationId;

    /** Entry this one reverses, set only on compensating entries. */
    @Column(name = "reverses_entry_ref", length = 40)
    private String reversesEntryRef;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "posted_by", length = 120)
    private String postedBy;

    /** Fencing token of the lock this entry was written under. */
    @Column(name = "fencing_token")
    private Long fencingToken;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @PreUpdate
    private void rejectUpdate() {
        throw new UnsupportedOperationException(
                "The ledger is append-only. Post a REVERSAL entry instead of editing entry " + entryRef);
    }

    @PreRemove
    private void rejectDelete() {
        throw new UnsupportedOperationException(
                "The ledger is append-only. Entry " + entryRef + " cannot be deleted");
    }
}
