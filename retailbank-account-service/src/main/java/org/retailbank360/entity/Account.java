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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A bank account: the balance, the rules that constrain it, and the concurrency controls that keep
 * it correct when several people touch it at once.
 *
 * <h2>Three layers of concurrency control</h2>
 * <ol>
 *   <li>{@link #version} - optimistic locking. Any update that was computed from a stale read is
 *       rejected at flush time instead of silently overwriting a concurrent change.</li>
 *   <li>A {@code PESSIMISTIC_WRITE} row lock, taken by
 *       {@code AccountRepository.findByIdForUpdate} inside the money transaction. Balance updates
 *       are read-modify-write, so they must be serialised, not merely detected after the fact.</li>
 *   <li>{@link #lastFencingToken} - the token of the distributed lock under which the last movement
 *       was posted. A writer whose lock lapsed and was taken over carries an older token and is
 *       refused, which is what stops a paused process from resurrecting and corrupting a balance.</li>
 * </ol>
 */
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_accounts_number", columnNames = "account_number"),
        indexes = {
                @Index(name = "ix_accounts_customer", columnList = "customer_id"),
                @Index(name = "ix_accounts_status", columnList = "status")
        })
@Getter
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;

    @NotNull(message = "Customer id must not be null")
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @NotNull(message = "Account type must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    /**
     * Current balance. May go negative only up to {@link #overdraftLimit}, and only when
     * {@link #overdraftAllowed} is set.
     */
    @NotNull(message = "Balance must not be null")
    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @NotNull(message = "Status must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @NotBlank(message = "Currency must not be blank")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /** Floor the balance may not drop below on a non-overdraft account. */
    @NotNull
    @DecimalMin(value = "0.00", message = "Minimum balance must not be negative")
    @Column(name = "minimum_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal minimumBalance = BigDecimal.ZERO;

    @Column(name = "overdraft_allowed", nullable = false)
    private boolean overdraftAllowed = false;

    /** How far below zero the balance may go when overdraft is permitted. */
    @NotNull
    @DecimalMin(value = "0.00", message = "Overdraft limit must not be negative")
    @Column(name = "overdraft_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal overdraftLimit = BigDecimal.ZERO;

    /** Ceiling on the total value transferred out of this account in one calendar day. */
    @NotNull
    @DecimalMin(value = "0.00", message = "Daily transfer limit must not be negative")
    @Column(name = "daily_transfer_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyTransferLimit = new BigDecimal("200000.00");

    /** Running total for {@link #dailyLimitDate}; reset lazily when the date rolls over. */
    @Column(name = "daily_transferred_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyTransferredAmount = BigDecimal.ZERO;

    @Column(name = "daily_limit_date")
    private LocalDate dailyLimitDate;

    /** Fencing token of the lock under which the last balance change was applied. */
    @Column(name = "last_fencing_token")
    private Long lastFencingToken;

    @NotNull(message = "Opened date must not be null")
    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** How much may still be taken out: balance, less the floor, plus any overdraft head-room. */
    @Transient
    public BigDecimal getAvailableBalance() {
        BigDecimal available = balance.subtract(minimumBalance);
        if (overdraftAllowed) {
            available = available.add(overdraftLimit);
        }
        return available.max(BigDecimal.ZERO);
    }

    /** Debits are only allowed on a fully active account. */
    @Transient
    public boolean isDebitable() {
        return status == AccountStatus.ACTIVE;
    }

    /** A dormant account can still receive money; a frozen or closed one cannot. */
    @Transient
    public boolean isCreditable() {
        return status == AccountStatus.ACTIVE || status == AccountStatus.INACTIVE;
    }

    /** Value already transferred out today, treating a stale counter as zero. */
    @Transient
    public BigDecimal getTransferredToday() {
        return LocalDate.now().equals(dailyLimitDate) ? dailyTransferredAmount : BigDecimal.ZERO;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (openedAt == null) {
            openedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
