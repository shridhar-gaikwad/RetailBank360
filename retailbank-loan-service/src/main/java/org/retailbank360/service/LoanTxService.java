package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.constants.RepaymentStatus;
import org.retailbank360.entity.Loan;
import org.retailbank360.entity.LoanRepayment;
import org.retailbank360.repository.LoanRepaymentRepository;
import org.retailbank360.repository.LoanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional state changes on a loan.
 *
 * <p>Split from {@code LoanServiceImpl} for the same reason as elsewhere: the resource lock is taken
 * outside the transaction, and a remote ledger call must never happen while a database transaction
 * is open. Keeping the two apart is what lets a disbursement be a proper saga - a short local
 * transaction, then the remote credit, then another short local transaction - rather than a
 * connection held open across the network.</p>
 */
@Slf4j
@Service
public class LoanTxService {

    private final LoanRepository loanRepository;
    private final LoanRepaymentRepository repaymentRepository;
    private final AmortizationCalculator amortizationCalculator;

    public LoanTxService(LoanRepository loanRepository,
                         LoanRepaymentRepository repaymentRepository,
                         AmortizationCalculator amortizationCalculator) {
        this.loanRepository = loanRepository;
        this.repaymentRepository = repaymentRepository;
        this.amortizationCalculator = amortizationCalculator;
    }

    /** Records the officer decision on an application. */
    @Transactional
    public Loan decide(Long loanId, LoanStatus decision, String reason) {
        Loan loan = lock(loanId);
        if (loan.getStatus() != LoanStatus.APPLIED) {
            throw new BusinessRuleViolationException(
                    "Loan " + loan.getLoanRef() + " is " + loan.getStatus() + " and no longer awaiting a decision");
        }
        loan.setStatus(decision);
        loan.setDecisionReason(reason);
        loan.setDecidedBy(SecurityUtils.currentUsername());
        loan.setDecidedAt(LocalDateTime.now());

        log.info("Loan {} {} by {}: {}", loan.getLoanRef(), decision, loan.getDecidedBy(), reason);
        return loanRepository.save(loan);
    }

    /**
     * First half of the disbursement saga: builds the schedule and moves the loan to
     * {@code DISBURSING}.
     *
     * <p>Committing this state <em>before</em> the ledger is called is what makes the operation
     * recoverable. If the process dies during the credit, the loan is found in {@code DISBURSING} and
     * the recovery job can ask the ledger what actually happened, instead of leaving a loan that was
     * funded but never recorded.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Loan beginDisbursement(Long loanId, String disbursementReference) {
        Loan loan = lock(loanId);

        if (loan.getStatus() == LoanStatus.DISBURSED) {
            throw new BusinessRuleViolationException("LOAN_ALREADY_DISBURSED",
                    "Loan " + loan.getLoanRef() + " has already been disbursed");
        }
        if (loan.getStatus() != LoanStatus.APPROVED && loan.getStatus() != LoanStatus.DISBURSING) {
            throw new BusinessRuleViolationException(
                    "Only an APPROVED loan can be disbursed; " + loan.getLoanRef() + " is " + loan.getStatus());
        }

        if (loan.getSchedule().isEmpty()) {
            LocalDate firstDueDate = LocalDate.now().plusMonths(1);
            loan.setFirstDueDate(firstDueDate);
            for (LoanRepayment installment : amortizationCalculator.buildSchedule(loan, firstDueDate)) {
                loan.addInstallment(installment);
            }
            log.info("Built a {}-instalment schedule for loan {}", loan.getSchedule().size(), loan.getLoanRef());
        }

        loan.setStatus(LoanStatus.DISBURSING);
        loan.setDisbursementReference(disbursementReference);
        loan.setDisbursementStartedAt(LocalDateTime.now());
        return loanRepository.save(loan);
    }

    /** Second half of the saga: the ledger confirmed the credit, so the loan becomes live. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Loan completeDisbursement(Long loanId) {
        Loan loan = lock(loanId);
        loan.setStatus(LoanStatus.DISBURSED);
        loan.setOutstandingPrincipal(loan.getPrincipal());
        loan.setDisbursedAt(LocalDateTime.now());
        loan.setDisbursementStartedAt(null);
        log.info("Loan {} disbursed: {} {} credited to account {}", loan.getLoanRef(),
                loan.getPrincipal(), loan.getCurrency(), loan.getDisbursementAccountId());
        return loanRepository.save(loan);
    }

    /**
     * Compensating action: the ledger refused the credit, so the loan goes back to APPROVED.
     *
     * <p>The schedule is discarded as well, so a later retry rebuilds it against the disbursement
     * date that actually happens rather than the one that did not.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Loan abortDisbursement(Long loanId, String reason) {
        Loan loan = lock(loanId);
        if (loan.getStatus() != LoanStatus.DISBURSING) {
            return loan;
        }
        loan.getSchedule().clear();
        loan.setFirstDueDate(null);
        loan.setDisbursementReference(null);
        loan.setDisbursementStartedAt(null);
        loan.setStatus(LoanStatus.APPROVED);
        loan.setDecisionReason("Disbursement rolled back: " + reason);

        log.warn("Rolled the disbursement of loan {} back to APPROVED: {}", loan.getLoanRef(), reason);
        return loanRepository.save(loan);
    }

    /**
     * Applies a collected payment to the schedule, oldest instalment first.
     *
     * <p>Runs after the account has actually been debited, so the money exists before it is
     * allocated. If this step fails the caller reverses the debit.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Loan applyRepayment(Long loanId, BigDecimal amount, String paymentReference) {
        Loan loan = lock(loanId);
        if (loan.getStatus() != LoanStatus.DISBURSED) {
            throw new BusinessRuleViolationException(
                    "Only a DISBURSED loan can take repayments; " + loan.getLoanRef() + " is " + loan.getStatus());
        }

        List<LoanRepayment> outstanding = repaymentRepository.findOutstanding(loanId);
        if (outstanding.isEmpty()) {
            throw new BusinessRuleViolationException("Loan " + loan.getLoanRef() + " has nothing left to pay");
        }

        BigDecimal remaining = MoneyUtil.normalize(amount);
        BigDecimal principalRepaid = BigDecimal.ZERO;
        LocalDateTime now = LocalDateTime.now();

        for (LoanRepayment installment : outstanding) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal due = installment.getRemaining();
            BigDecimal applied = remaining.min(due);

            installment.setPaidAmount(MoneyUtil.normalize(installment.getPaidAmount().add(applied)));
            installment.setPaymentReference(paymentReference);

            if (installment.getRemaining().compareTo(BigDecimal.ZERO) <= 0) {
                installment.setStatus(RepaymentStatus.PAID);
                installment.setPaidAt(now);
                principalRepaid = principalRepaid.add(installment.getPrincipalComponent());
            } else {
                installment.setStatus(RepaymentStatus.PARTIALLY_PAID);
                // A partial payment settles interest first, and only the surplus touches principal.
                BigDecimal towardsPrincipal = applied.subtract(installment.getInterestComponent()).max(BigDecimal.ZERO);
                principalRepaid = principalRepaid.add(towardsPrincipal);
            }
            remaining = MoneyUtil.normalize(remaining.subtract(applied));
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            // More was collected than the loan owed. Refusing here rolls the debit back, which is
            // safer than silently keeping the surplus.
            throw new BusinessRuleViolationException("OVERPAYMENT",
                    "The payment exceeds the outstanding balance by " + remaining
                            + ". Pay the exact outstanding amount instead.");
        }

        loan.setTotalRepaid(MoneyUtil.normalize(loan.getTotalRepaid().add(amount)));
        loan.setOutstandingPrincipal(
                MoneyUtil.normalize(loan.getOutstandingPrincipal().subtract(principalRepaid)).max(BigDecimal.ZERO));

        if (repaymentRepository.findOutstanding(loanId).isEmpty()
                || loan.getOutstandingPrincipal().compareTo(BigDecimal.ZERO) == 0) {
            loan.setStatus(LoanStatus.CLOSED);
            loan.setClosedAt(now);
            loan.setOutstandingPrincipal(BigDecimal.ZERO.setScale(MoneyUtil.SCALE));
            log.info("Loan {} fully repaid and closed", loan.getLoanRef());
        }

        repaymentRepository.saveAll(outstanding);
        return loanRepository.save(loan);
    }

    /**
     * Writes a loan off as defaulted.
     *
     * <p>One way and final: a defaulted loan takes no further automatic collection, and the customer
     * is barred from new borrowing by the eligibility rules. Applied under the caller's lock.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Loan markDefaulted(Long loanId, String reason) {
        Loan loan = lock(loanId);
        if (loan.getStatus() != LoanStatus.DISBURSED) {
            return loan;
        }
        loan.setStatus(LoanStatus.DEFAULTED);
        loan.setDecisionReason(reason);
        log.warn("Loan {} written off as DEFAULTED: {}", loan.getLoanRef(), reason);
        return loanRepository.save(loan);
    }

    /** Marks instalments that are past their due date, so overdue reporting is accurate. */
    @Transactional
    public int markOverdueInstallments() {
        List<LoanRepayment> pastDue = repaymentRepository.findPastDue(LocalDate.now(),
                List.of(RepaymentStatus.PENDING, RepaymentStatus.PARTIALLY_PAID));
        for (LoanRepayment installment : pastDue) {
            installment.setStatus(RepaymentStatus.OVERDUE);
        }
        repaymentRepository.saveAll(pastDue);
        return pastDue.size();
    }

    private Loan lock(Long loanId) {
        return loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }
}
