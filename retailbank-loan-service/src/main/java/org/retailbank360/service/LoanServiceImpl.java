package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.client.AccountServiceClient;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.client.dto.CustomerProfile;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.exception.BusinessException;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ExternalServiceException;
import org.retailbank360.common.exception.InsufficientFundsException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.idempotency.IdempotencyService;
import org.retailbank360.common.notification.NotificationPublisher;
import org.retailbank360.common.lock.LockRequest;
import org.retailbank360.common.lock.LockResourceTypes;
import org.retailbank360.common.lock.LockTemplate;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.common.web.PageRequests;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.constants.LoanServicingRules;
import org.retailbank360.constants.LoanType;
import org.retailbank360.dto.EligibilityResponse;
import org.retailbank360.dto.LoanApplicationRequest;
import org.retailbank360.dto.LoanDecisionRequest;
import org.retailbank360.dto.LoanResponse;
import org.retailbank360.dto.RepaymentRequest;
import org.retailbank360.dto.RepaymentScheduleResponse;
import org.retailbank360.entity.Loan;
import org.retailbank360.entity.LoanRepayment;
import org.retailbank360.repository.LoanRepaymentRepository;
import org.retailbank360.repository.LoanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Loan origination and servicing.
 *
 * <h2>Disbursement as a saga</h2>
 * Creating the loan record and crediting the customer's account must look atomic, but they live in
 * two databases. The requirement asks for a distributed-transaction pattern, and this is the
 * orchestrated saga:
 * <ol>
 *   <li><b>Lock</b> both {@code LOAN:id} and {@code ACCOUNT:id}, in sorted key order, before any
 *       state changes. Two officers clicking "disburse" simultaneously therefore serialise.</li>
 *   <li><b>Commit intent</b>: the schedule is built and the loan moves to {@code DISBURSING} in a
 *       short local transaction. This is what survives a crash during the next step.</li>
 *   <li><b>Credit the account</b> through the ledger, keyed by the loan reference so a retry cannot
 *       fund the loan twice.</li>
 *   <li><b>Confirm</b> with a second short transaction, or <b>compensate</b> by rolling the loan back
 *       to {@code APPROVED} if the ledger refused.</li>
 * </ol>
 * The remote call sits deliberately between two transactions rather than inside one: holding a
 * database transaction open across a network call pins a connection and turns a slow downstream into
 * a connection-pool outage.
 */
@Slf4j
@Service
public class LoanServiceImpl implements LoanService {

    private static final String SCOPE_DISBURSEMENT = "LOAN_DISBURSEMENT";
    private static final String SCOPE_REPAYMENT = "LOAN_REPAYMENT";

    /** A loan stuck in DISBURSING for longer than this needs its outcome checked against the ledger. */
    private static final int STUCK_DISBURSEMENT_MINUTES = 2;

    private final LoanRepository loanRepository;
    private final LoanRepaymentRepository repaymentRepository;
    private final LoanTxService loanTxService;
    private final LoanEligibilityService eligibilityService;
    private final AmortizationCalculator amortizationCalculator;
    private final CustomerServiceClient customerClient;
    private final AccountServiceClient accountClient;
    private final LockTemplate lockTemplate;
    private final IdempotencyService idempotencyService;
    private final AuditPublisher auditPublisher;
    private final NotificationPublisher notificationPublisher;

    public LoanServiceImpl(LoanRepository loanRepository,
                           LoanRepaymentRepository repaymentRepository,
                           LoanTxService loanTxService,
                           LoanEligibilityService eligibilityService,
                           AmortizationCalculator amortizationCalculator,
                           CustomerServiceClient customerClient,
                           AccountServiceClient accountClient,
                           LockTemplate lockTemplate,
                           IdempotencyService idempotencyService,
                           AuditPublisher auditPublisher,
                           NotificationPublisher notificationPublisher) {
        this.loanRepository = loanRepository;
        this.repaymentRepository = repaymentRepository;
        this.loanTxService = loanTxService;
        this.eligibilityService = eligibilityService;
        this.amortizationCalculator = amortizationCalculator;
        this.customerClient = customerClient;
        this.accountClient = accountClient;
        this.lockTemplate = lockTemplate;
        this.idempotencyService = idempotencyService;
        this.auditPublisher = auditPublisher;
        this.notificationPublisher = notificationPublisher;
    }

    @Override
    public EligibilityResponse checkEligibility(LoanApplicationRequest request) {
        SecurityUtils.requireCustomerAccess(request.getCustomerId());
        BigDecimal rate = resolveRate(request);
        return eligibilityService.evaluate(fetchProfile(request.getCustomerId()),
                request.getPrincipal(), rate, request.getTenureMonths());
    }

    @Override
    @Transactional
    public LoanResponse apply(LoanApplicationRequest request) {
        SecurityUtils.requireCustomerAccess(request.getCustomerId());

        CustomerProfile profile = fetchProfile(request.getCustomerId());
        assertAccountBelongsToCustomer(request.getDisbursementAccountId(), request.getCustomerId());

        BigDecimal rate = resolveRate(request);
        EligibilityResponse eligibility = eligibilityService.evaluate(
                profile, request.getPrincipal(), rate, request.getTenureMonths());

        Loan loan = new Loan();
        loan.setLoanRef(IdGenerator.reference("LOAN"));
        loan.setCustomerId(request.getCustomerId());
        loan.setDisbursementAccountId(request.getDisbursementAccountId());
        loan.setLoanType(request.getLoanType());
        loan.setPrincipal(MoneyUtil.normalize(request.getPrincipal()));
        loan.setInterestRate(rate);
        loan.setTenureMonths(request.getTenureMonths());
        loan.setCurrency(request.getCurrency() == null || request.getCurrency().isBlank()
                ? "INR" : request.getCurrency().toUpperCase());
        loan.setEmiAmount(eligibility.getProposedEmi());
        loan.setTotalInterest(amortizationCalculator.calculateTotalInterest(
                loan.getPrincipal(), loan.getEmiAmount(), loan.getTenureMonths()));
        loan.setCreditScoreAtApplication(profile.getCreditScore());
        loan.setAnnualIncomeAtApplication(profile.getAnnualIncome());
        loan.setAppliedAt(LocalDateTime.now());

        if (eligibility.isEligible()) {
            loan.setStatus(LoanStatus.APPLIED);
        } else {
            // A failed application is still recorded, with every rule it missed, so the customer can
            // be told exactly why and the decision remains auditable.
            loan.setStatus(LoanStatus.REJECTED);
            loan.setDecisionReason(String.join("; ", eligibility.getReasons()));
            loan.setDecidedBy("eligibility-rules");
            loan.setDecidedAt(LocalDateTime.now());
        }

        Loan saved = loanRepository.save(loan);
        log.info("Loan application {} for customer {} recorded as {}",
                saved.getLoanRef(), saved.getCustomerId(), saved.getStatus());

        auditPublisher.publish(AuditEventRequest.builder()
                .action(eligibility.isEligible() ? AuditActions.LOAN_APPLIED : AuditActions.LOAN_REJECTED)
                .entityType("LOAN")
                .entityId(saved.getLoanRef())
                .operationId(saved.getLoanRef())
                .build()
                .with("principal", saved.getPrincipal())
                .with("tenureMonths", saved.getTenureMonths())
                .with("reasons", eligibility.getReasons()));

        return LoanResponse.from(saved);
    }

    @Override
    public LoanResponse approve(Long loanId, LoanDecisionRequest request) {
        Loan decided = lockTemplate.executeWithLock(
                LockRequest.of(LockResourceTypes.LOAN, loanId, "loan-approve-" + loanId),
                handle -> loanTxService.decide(loanId, LoanStatus.APPROVED, request.getReason()));
        auditPublisher.publishSuccess(AuditActions.LOAN_APPROVED, "LOAN", decided.getLoanRef(),
                decided.getLoanRef());
        return LoanResponse.from(decided);
    }

    @Override
    public LoanResponse reject(Long loanId, LoanDecisionRequest request) {
        Loan decided = lockTemplate.executeWithLock(
                LockRequest.of(LockResourceTypes.LOAN, loanId, "loan-reject-" + loanId),
                handle -> loanTxService.decide(loanId, LoanStatus.REJECTED, request.getReason()));
        auditPublisher.publishSuccess(AuditActions.LOAN_REJECTED, "LOAN", decided.getLoanRef(),
                decided.getLoanRef());
        return LoanResponse.from(decided);
    }

    @Override
    public LoanResponse disburse(Long loanId) {
        Loan loan = requireLoan(loanId);
        String idempotencyKey = "disburse-" + loan.getLoanRef();

        return idempotencyService.execute(SCOPE_DISBURSEMENT, idempotencyKey, loanId, LoanResponse.class,
                () -> lockTemplate.executeWithLocksAndRetry(
                        "disburse " + loan.getLoanRef(),
                        List.of(
                                LockRequest.of(LockResourceTypes.LOAN, loanId, loan.getLoanRef()),
                                LockRequest.of(LockResourceTypes.ACCOUNT, loan.getDisbursementAccountId(),
                                        loan.getLoanRef())),
                        handles -> runDisbursementSaga(loanId)));
    }

    /** Steps 2 to 4 of the disbursement saga; the locks are already held by the caller. */
    private LoanResponse runDisbursementSaga(Long loanId) {
        String disbursementReference = IdGenerator.reference("DISB");
        Loan loan = loanTxService.beginDisbursement(loanId, disbursementReference);

        try {
            accountClient.credit(MoneyMovementRequest.builder()
                    .accountId(loan.getDisbursementAccountId())
                    .amount(loan.getPrincipal())
                    .currency(loan.getCurrency())
                    .movementType("LOAN_DISBURSEMENT")
                    .reference(disbursementReference)
                    .operationId(loan.getLoanRef())
                    .description("Disbursement of loan " + loan.getLoanRef())
                    .initiatedBy(SecurityUtils.currentUsername())
                    .build(), disbursementReference);

        } catch (BusinessException e) {
            // The ledger refused outright, so nothing was credited: undo the intent.
            loanTxService.abortDisbursement(loanId, e.getMessage());
            auditPublisher.publishFailure(AuditActions.LOAN_DISBURSEMENT_FAILED, "LOAN",
                    loan.getLoanRef(), loan.getLoanRef(), e.getMessage());
            throw e;

        } catch (RuntimeException e) {
            // Ambiguous: the credit may have committed. Leave the loan DISBURSING and let the
            // recovery job settle it against the ledger rather than guessing.
            log.error("Disbursement of loan {} has an unknown outcome; left DISBURSING for recovery",
                    loan.getLoanRef(), e);
            auditPublisher.publishFailure(AuditActions.LOAN_DISBURSEMENT_FAILED, "LOAN",
                    loan.getLoanRef(), loan.getLoanRef(), "Unknown outcome: " + e.getMessage());
            throw new BusinessRuleViolationException("DISBURSEMENT_OUTCOME_UNKNOWN",
                    "The ledger did not confirm the disbursement of " + loan.getLoanRef()
                            + ". It will be reconciled automatically; query the loan before retrying.");
        }

        Loan disbursed = loanTxService.completeDisbursement(loanId);
        auditPublisher.publish(AuditEventRequest.builder()
                .action(AuditActions.LOAN_DISBURSED)
                .entityType("LOAN")
                .entityId(disbursed.getLoanRef())
                .operationId(disbursed.getLoanRef())
                .build()
                .with("principal", disbursed.getPrincipal())
                .with("accountId", disbursed.getDisbursementAccountId())
                .with("disbursementReference", disbursementReference));

        notificationPublisher.notifyCustomer(disbursed.getCustomerId(), "LOAN_DISBURSED",
                disbursed.getLoanRef(),
                "Your loan " + disbursed.getLoanRef() + " has been disbursed",
                disbursed.getPrincipal() + " " + disbursed.getCurrency() + " has been credited to your "
                        + "account. First instalment of " + disbursed.getEmiAmount() + " is due on "
                        + disbursed.getFirstDueDate() + ".");

        return LoanResponse.from(disbursed);
    }

    @Override
    public LoanResponse repay(Long loanId, RepaymentRequest request) {
        Loan loan = requireLoan(loanId);
        SecurityUtils.requireCustomerAccess(loan.getCustomerId());

        String key = request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                ? IdGenerator.token() : request.getIdempotencyKey();

        return idempotencyService.execute(SCOPE_REPAYMENT, key, request, LoanResponse.class,
                () -> lockTemplate.executeWithLocksAndRetry(
                        "repay " + loan.getLoanRef(),
                        List.of(
                                LockRequest.of(LockResourceTypes.LOAN, loanId, loan.getLoanRef()),
                                LockRequest.of(LockResourceTypes.ACCOUNT, loan.getDisbursementAccountId(),
                                        loan.getLoanRef())),
                        handles -> runRepaymentSaga(loan, request)));
    }

    /**
     * Collects the money first, then allocates it.
     *
     * <p>Doing it in this order means a failure to allocate is recoverable by reversing a debit that
     * definitely happened. The reverse order - allocate, then collect - would leave instalments
     * marked paid with no money behind them, which is far worse.</p>
     */
    private LoanResponse runRepaymentSaga(Loan loan, RepaymentRequest request) {
        String paymentReference = IdGenerator.reference("REPAY");

        accountClient.debit(MoneyMovementRequest.builder()
                .accountId(loan.getDisbursementAccountId())
                .amount(MoneyUtil.normalize(request.getAmount()))
                .currency(loan.getCurrency())
                .movementType("LOAN_REPAYMENT")
                .reference(paymentReference)
                .operationId(loan.getLoanRef())
                .description(request.getDescription() == null
                        ? "Repayment of loan " + loan.getLoanRef() : request.getDescription())
                .initiatedBy(SecurityUtils.currentUsername())
                .build(), paymentReference);

        try {
            Loan updated = loanTxService.applyRepayment(loan.getId(), request.getAmount(), paymentReference);

            auditPublisher.publish(AuditEventRequest.builder()
                    .action(updated.getStatus() == LoanStatus.CLOSED
                            ? AuditActions.LOAN_CLOSED : AuditActions.LOAN_REPAYMENT_POSTED)
                    .entityType("LOAN")
                    .entityId(updated.getLoanRef())
                    .operationId(updated.getLoanRef())
                    .build()
                    .with("amount", request.getAmount())
                    .with("outstandingPrincipal", updated.getOutstandingPrincipal())
                    .with("paymentReference", paymentReference));

            notificationPublisher.notifyCustomer(updated.getCustomerId(),
                    updated.getStatus() == LoanStatus.CLOSED ? "LOAN_CLOSED" : "LOAN_REPAYMENT_RECEIVED",
                    updated.getLoanRef(),
                    updated.getStatus() == LoanStatus.CLOSED
                            ? "Loan " + updated.getLoanRef() + " is fully repaid"
                            : "Repayment received for loan " + updated.getLoanRef(),
                    "We received " + request.getAmount() + " " + updated.getCurrency() + ". Outstanding "
                            + "principal is now " + updated.getOutstandingPrincipal() + ".");

            return LoanResponse.from(updated);

        } catch (RuntimeException e) {
            // Compensating action: the money left the account but could not be allocated, so put it
            // back rather than leaving the customer short.
            log.error("Could not apply repayment {} to loan {}; reversing the debit",
                    paymentReference, loan.getLoanRef(), e);
            try {
                accountClient.reverse(paymentReference, "Repayment could not be applied: " + e.getMessage(),
                        List.of(loan.getDisbursementAccountId()));
            } catch (RuntimeException reversalFailure) {
                log.error("Reversal of {} also failed. Manual intervention is required.",
                        paymentReference, reversalFailure);
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LoanResponse getLoan(Long loanId) {
        Loan loan = requireLoan(loanId);
        SecurityUtils.requireCustomerAccess(loan.getCustomerId());
        return LoanResponse.from(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public LoanResponse getLoanByRef(String loanRef) {
        Loan loan = loanRepository.findByLoanRef(loanRef)
                .orElseThrow(() -> new ResourceNotFoundException("No loan with reference " + loanRef));
        SecurityUtils.requireCustomerAccess(loan.getCustomerId());
        return LoanResponse.from(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanResponse> getAllLoans(int page, int size) {
        return loanRepository.findAll(PageRequests.of(page, size))
                .map(LoanResponse::from)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanResponse> getLoansByCustomer(Long customerId) {
        SecurityUtils.requireCustomerAccess(customerId);
        return loanRepository.findByCustomerId(customerId).stream().map(LoanResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepaymentScheduleResponse> getSchedule(Long loanId) {
        Loan loan = requireLoan(loanId);
        SecurityUtils.requireCustomerAccess(loan.getCustomerId());
        return repaymentRepository.findByLoanIdOrderByInstallmentNumberAsc(loanId).stream()
                .map(RepaymentScheduleResponse::from)
                .toList();
    }

    @Override
    public int recoverStuckDisbursements() {
        List<Loan> stuck = loanRepository.findStuckDisbursements(
                LocalDateTime.now().minusMinutes(STUCK_DISBURSEMENT_MINUTES));
        int settled = 0;

        for (Loan loan : stuck) {
            try {
                List<Map<String, Object>> entries =
                        accountClient.entriesForReference(loan.getDisbursementReference());
                if (entries != null && !entries.isEmpty()) {
                    loanTxService.completeDisbursement(loan.getId());
                    log.warn("Recovered loan {}: the ledger had credited it after all", loan.getLoanRef());
                } else {
                    loanTxService.abortDisbursement(loan.getId(), "Ledger holds no entries for the disbursement");
                    log.warn("Recovered loan {}: the credit never happened, rolled back to APPROVED",
                            loan.getLoanRef());
                }
                settled++;
            } catch (ResourceNotFoundException e) {
                loanTxService.abortDisbursement(loan.getId(), "Ledger holds no entries for the disbursement");
                settled++;
            } catch (RuntimeException e) {
                log.error("Could not recover loan {}; leaving it DISBURSING", loan.getLoanRef(), e);
            }
        }
        return settled;
    }

    @Override
    public int markOverdueInstallments() {
        return loanTxService.markOverdueInstallments();
    }

    /**
     * Automatic EMI collection.
     *
     * <p>Each instalment is collected on its own, through the same locked, idempotent repayment path
     * a manual payment uses. The idempotency key is derived from the loan and the instalment number,
     * so a sweep that runs twice in one day collects each instalment exactly once - which matters,
     * because these jobs run on every instance.</p>
     *
     * <p>A customer who cannot cover the instalment is skipped, not retried: pushing the account into
     * an unauthorised overdraft to collect a loan payment would be worse than the missed payment.</p>
     */
    @Override
    public int collectDueInstallments() {
        List<LoanRepayment> due = repaymentRepository.findCollectible(
                LocalDate.now(), PageRequests.of(0, LoanServicingRules.MAX_COLLECTIONS_PER_RUN));

        int collected = 0;
        for (LoanRepayment installment : due) {
            Loan loan = installment.getLoan();
            BigDecimal amount = installment.getRemaining();
            if (!MoneyUtil.isPositive(amount)) {
                continue;
            }

            RepaymentRequest request = new RepaymentRequest();
            request.setAmount(amount);
            request.setIdempotencyKey("auto-" + loan.getLoanRef() + "-" + installment.getInstallmentNumber());
            request.setDescription("Automatic collection of instalment "
                    + installment.getInstallmentNumber() + " for loan " + loan.getLoanRef());

            try {
                repay(loan.getId(), request);
                collected++;
                log.info("Collected instalment {} of loan {}",
                        installment.getInstallmentNumber(), loan.getLoanRef());
            } catch (InsufficientFundsException e) {
                log.info("Instalment {} of loan {} could not be collected: {}",
                        installment.getInstallmentNumber(), loan.getLoanRef(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Automatic collection failed for instalment {} of loan {}",
                        installment.getInstallmentNumber(), loan.getLoanRef(), e);
            }
        }
        return collected;
    }

    @Override
    public int markDefaultedLoans() {
        LocalDate cutoff = LocalDate.now().minusDays(LoanServicingRules.DAYS_OVERDUE_BEFORE_DEFAULT);
        List<Long> loanIds = repaymentRepository.findLongOverdue(cutoff).stream()
                .map(installment -> installment.getLoan().getId())
                .distinct()
                .toList();

        int defaulted = 0;
        for (Long loanId : loanIds) {
            try {
                Loan written = lockTemplate.executeWithLock(
                        LockRequest.of(LockResourceTypes.LOAN, loanId, "default-" + loanId),
                        handle -> loanTxService.markDefaulted(loanId,
                                "Unpaid for more than " + LoanServicingRules.DAYS_OVERDUE_BEFORE_DEFAULT
                                        + " days"));
                if (written.getStatus() == LoanStatus.DEFAULTED) {
                    defaulted++;
                    auditPublisher.publishFailure(AuditActions.LOAN_CLOSED, "LOAN", written.getLoanRef(),
                            written.getLoanRef(), "Written off as DEFAULTED");
                    notificationPublisher.notifyCustomer(written.getCustomerId(), "LOAN_DEFAULTED",
                            written.getLoanRef(),
                            "Loan " + written.getLoanRef() + " has been marked in default",
                            "Your loan has been unpaid for more than "
                                    + LoanServicingRules.DAYS_OVERDUE_BEFORE_DEFAULT
                                    + " days. Please contact the branch.");
                }
            } catch (RuntimeException e) {
                log.error("Could not write off loan {}", loanId, e);
            }
        }
        return defaulted;
    }

    /** Product rate bands, used when the application does not name a rate. */
    private BigDecimal resolveRate(LoanApplicationRequest request) {
        if (request.getInterestRate() != null) {
            return request.getInterestRate();
        }
        LoanType type = request.getLoanType();
        return switch (type) {
            case HOME -> new BigDecimal("8.500");
            case VEHICLE -> new BigDecimal("10.250");
            case EDUCATION -> new BigDecimal("9.000");
            case PERSONAL -> new BigDecimal("14.000");
        };
    }

    /** The account the money lands in must belong to the applicant. */
    private void assertAccountBelongsToCustomer(Long accountId, Long customerId) {
        Map<String, Object> summary;
        try {
            summary = accountClient.accountSummary(accountId);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExternalServiceException("account-service", "unable to verify account " + accountId, e);
        }
        if (summary == null || summary.get("customerId") == null) {
            throw new ResourceNotFoundException("Account", accountId);
        }
        if (!customerId.equals(Long.valueOf(String.valueOf(summary.get("customerId"))))) {
            throw new BusinessRuleViolationException("ACCOUNT_NOT_OWNED",
                    "Account " + accountId + " does not belong to customer " + customerId);
        }
        if (!"ACTIVE".equals(String.valueOf(summary.get("status")))) {
            throw new BusinessRuleViolationException("ACCOUNT_NOT_ACTIVE",
                    "Account " + accountId + " is " + summary.get("status"));
        }
    }

    private CustomerProfile fetchProfile(Long customerId) {
        try {
            CustomerProfile profile = customerClient.getProfile(customerId);
            if (profile == null || profile.getCustomerId() == null) {
                throw new ResourceNotFoundException("Customer", customerId);
            }
            return profile;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExternalServiceException("customer-service", "unable to read customer " + customerId, e);
        }
    }

    private Loan requireLoan(Long loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }
}
