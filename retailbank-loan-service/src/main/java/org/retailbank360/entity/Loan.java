package org.retailbank360.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.constants.LoanType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A consumer loan, its amortisation schedule and the state of its disbursement.
 *
 * <p>The eligibility inputs used at decision time - credit score and income - are copied onto the
 * loan rather than re-read later. A decision has to remain explainable months afterwards, even once
 * the customer's profile has moved on.</p>
 */
@Entity
@Table(name = "loans",
        uniqueConstraints = @UniqueConstraint(name = "uk_loans_ref", columnNames = "loan_ref"),
        indexes = {
                @Index(name = "ix_loans_customer", columnList = "customer_id"),
                @Index(name = "ix_loans_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_ref", nullable = false, unique = true, length = 40)
    private String loanRef;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Account the principal is paid into, and that repayments are collected from. */
    @Column(name = "disbursement_account_id", nullable = false)
    private Long disbursementAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "loan_type", nullable = false, length = 20)
    private LoanType loanType;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    /** Nominal annual rate, as a percentage, e.g. 12.50. */
    @Column(name = "interest_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal interestRate;

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "emi_amount", precision = 19, scale = 2)
    private BigDecimal emiAmount;

    /** Principal still owed. Drops as instalments are paid; reaching zero closes the loan. */
    @Column(name = "outstanding_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingPrincipal = BigDecimal.ZERO;

    @Column(name = "total_interest", precision = 19, scale = 2)
    private BigDecimal totalInterest;

    @Column(name = "total_repaid", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalRepaid = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LoanStatus status = LoanStatus.APPLIED;

    /** Snapshot of the eligibility inputs, kept so the decision stays explainable. */
    @Column(name = "credit_score_at_application")
    private Integer creditScoreAtApplication;

    @Column(name = "annual_income_at_application", precision = 19, scale = 2)
    private BigDecimal annualIncomeAtApplication;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_by", length = 120)
    private String decidedBy;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    /**
     * When the disbursement saga entered {@code DISBURSING}.
     *
     * <p>Deliberately separate from {@code updatedAt}, which the lifecycle callback refreshes on
     * every save and therefore cannot answer "how long has this been stuck?".</p>
     */
    @Column(name = "disbursement_started_at")
    private LocalDateTime disbursementStartedAt;

    /** Ledger reference of the credit that funded this loan. Also its idempotency key. */
    @Column(name = "disbursement_reference", length = 40)
    private String disbursementReference;

    @Column(name = "first_due_date")
    private LocalDate firstDueDate;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("installmentNumber asc")
    private List<LoanRepayment> schedule = new ArrayList<>();

    public void addInstallment(LoanRepayment installment) {
        installment.setLoan(this);
        schedule.add(installment);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (appliedAt == null) {
            appliedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
