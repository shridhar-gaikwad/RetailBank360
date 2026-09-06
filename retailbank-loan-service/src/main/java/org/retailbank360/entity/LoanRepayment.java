package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.retailbank360.constants.RepaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One instalment of an amortisation schedule.
 *
 * <p>The principal and interest split is fixed at disbursement, so a customer can see exactly how
 * each payment is applied, and so a partial payment has an unambiguous destination.</p>
 */
@Entity
@Table(name = "loan_repayments",
        uniqueConstraints = @UniqueConstraint(name = "uk_repayment_loan_installment",
                columnNames = {"loan_id", "installment_number"}),
        indexes = {
                @Index(name = "ix_repayments_loan", columnList = "loan_id"),
                @Index(name = "ix_repayments_due", columnList = "due_date"),
                @Index(name = "ix_repayments_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;

    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "emi_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal emiAmount;

    @Column(name = "principal_component", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalComponent;

    @Column(name = "interest_component", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestComponent;

    /** Principal still owed once this instalment has been paid. */
    @Column(name = "outstanding_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingAfter;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RepaymentStatus status = RepaymentStatus.PENDING;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Ledger reference of the debit that settled this instalment. */
    @Column(name = "payment_reference", length = 40)
    private String paymentReference;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Amount still needed to close this instalment. */
    public BigDecimal getRemaining() {
        return emiAmount.subtract(paidAmount).max(BigDecimal.ZERO);
    }
}
