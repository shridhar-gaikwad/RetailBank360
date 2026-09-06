package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.client.AccountServiceClient;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.client.dto.CustomerProfile;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.InsufficientFundsException;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.constants.LoanType;
import org.retailbank360.constants.RepaymentStatus;
import org.retailbank360.dto.EligibilityResponse;
import org.retailbank360.dto.LoanApplicationRequest;
import org.retailbank360.dto.LoanDecisionRequest;
import org.retailbank360.dto.LoanResponse;
import org.retailbank360.dto.RepaymentRequest;
import org.retailbank360.repository.LoanRepository;
import org.retailbank360.support.LoanDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Loan origination, the disbursement saga and repayment posting.
 *
 * <p>account-service and customer-service are mocked, which is the point: the tests can then drive
 * the ledger into refusing a credit or failing outright, and assert that the saga compensates
 * correctly instead of leaving a loan funded on paper but not in fact.</p>
 */
@SpringBootTest
class LoanLifecycleIT {

    @MockitoBean
    private CustomerServiceClient customerServiceClient;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    @Autowired
    private LoanService loanService;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        LoanDatabaseCleaner.clean(jdbcTemplate);
        when(customerServiceClient.getProfile(anyLong())).thenReturn(creditworthyProfile());
        when(accountServiceClient.accountSummary(anyLong())).thenReturn(
                Map.of("accountId", 10L, "customerId", 1L, "currency", "INR", "status", "ACTIVE"));
        when(accountServiceClient.credit(any(MoneyMovementRequest.class), anyString()))
                .thenReturn(posted("500000.00"));
        when(accountServiceClient.debit(any(MoneyMovementRequest.class), anyString()))
                .thenReturn(posted("400000.00"));
    }

    @Test
    @DisplayName("A creditworthy application is accepted and priced")
    void acceptsACreditworthyApplication() {
        LoanResponse loan = loanService.apply(application(new BigDecimal("200000"), 24));

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.APPLIED);
        assertThat(loan.getEmiAmount()).isPositive();
        assertThat(loan.getInterestRate())
                .as("the personal-loan product rate applies when the request names none")
                .isEqualByComparingTo("14.000");
        assertThat(loan.getTotalInterest()).isPositive();
    }

    @Test
    @DisplayName("An application that fails the rules is recorded as REJECTED with every reason")
    void recordsAnIneligibleApplicationWithItsReasons() {
        CustomerProfile poorCredit = creditworthyProfile();
        poorCredit.setCreditScore(500);
        when(customerServiceClient.getProfile(anyLong())).thenReturn(poorCredit);

        LoanResponse loan = loanService.apply(application(new BigDecimal("200000"), 24));

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REJECTED);
        assertThat(loan.getDecisionReason()).contains("Credit score 500");
        assertThat(loan.getDecidedBy()).isEqualTo("eligibility-rules");
    }

    @Test
    @DisplayName("An amount beyond what the income supports is declined, with the affordable figure")
    void declinesAnUnaffordableAmount() {
        EligibilityResponse eligibility = loanService.checkEligibility(
                application(new BigDecimal("5000000"), 12));

        assertThat(eligibility.isEligible()).isFalse();
        assertThat(eligibility.getReasons()).isNotEmpty();
        assertThat(eligibility.getMaximumEligibleAmount())
                .as("the applicant is told what they could borrow instead of just being refused")
                .isPositive();
    }

    @Test
    @DisplayName("Disbursement builds the schedule, credits the account and marks the loan live")
    void disbursesAnApprovedLoan() {
        LoanResponse applied = loanService.apply(application(new BigDecimal("200000"), 12));
        loanService.approve(applied.getId(), decision("verified and approved"));

        LoanResponse disbursed = loanService.disburse(applied.getId());

        assertThat(disbursed.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(disbursed.getOutstandingPrincipal()).isEqualByComparingTo("200000.00");
        assertThat(disbursed.getDisbursementReference()).startsWith("DISB-");
        assertThat(disbursed.getFirstDueDate()).isEqualTo(LocalDate.now().plusMonths(1));
        assertThat(loanService.getSchedule(applied.getId())).hasSize(12);

        verify(accountServiceClient, times(1)).credit(any(MoneyMovementRequest.class), anyString());
    }

    @Test
    @DisplayName("Disbursing twice credits the account only once")
    void disbursementIsIdempotent() {
        LoanResponse applied = loanService.apply(application(new BigDecimal("200000"), 12));
        loanService.approve(applied.getId(), decision("approved"));

        LoanResponse first = loanService.disburse(applied.getId());
        LoanResponse second = loanService.disburse(applied.getId());

        assertThat(second.getDisbursementReference()).isEqualTo(first.getDisbursementReference());
        verify(accountServiceClient, times(1)).credit(any(MoneyMovementRequest.class), anyString());
    }

    @Test
    @DisplayName("A loan that has not been approved cannot be disbursed")
    void refusesToDisburseAnUnapprovedLoan() {
        LoanResponse applied = loanService.apply(application(new BigDecimal("200000"), 12));

        assertThatThrownBy(() -> loanService.disburse(applied.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an APPROVED loan");

        verify(accountServiceClient, never()).credit(any(MoneyMovementRequest.class), anyString());
    }

    @Test
    @DisplayName("If the ledger refuses the credit, the saga rolls the loan back to APPROVED")
    void compensatesWhenTheLedgerRefusesTheCredit() {
        when(accountServiceClient.credit(any(MoneyMovementRequest.class), anyString()))
                .thenThrow(new BusinessRuleViolationException("ACCOUNT_NOT_CREDITABLE", "Account is FROZEN"));

        LoanResponse applied = loanService.apply(application(new BigDecimal("200000"), 12));
        loanService.approve(applied.getId(), decision("approved"));

        assertThatThrownBy(() -> loanService.disburse(applied.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);

        LoanResponse rolledBack = loanService.getLoan(applied.getId());
        assertThat(rolledBack.getStatus())
                .as("no half-disbursed state survives a refusal")
                .isEqualTo(LoanStatus.APPROVED);
        assertThat(loanService.getSchedule(applied.getId()))
                .as("the schedule is discarded so a later retry rebuilds it from the real date")
                .isEmpty();
        assertThat(rolledBack.getDisbursementReference()).isNull();
        assertThat(rolledBack.getDecisionReason()).contains("rolled back");
    }

    @Test
    @DisplayName("An unknown disbursement outcome stays recoverable and is settled from the ledger")
    void recoversADisbursementWithAnUnknownOutcome() {
        when(accountServiceClient.credit(any(MoneyMovementRequest.class), anyString()))
                .thenThrow(new IllegalStateException("read timed out"));

        LoanResponse applied = loanService.apply(application(new BigDecimal("200000"), 12));
        loanService.approve(applied.getId(), decision("approved"));

        assertThatThrownBy(() -> loanService.disburse(applied.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("did not confirm");

        // The credit may or may not have committed, so the loan is deliberately left in DISBURSING
        // rather than guessed at either way.
        var stuck = loanRepository.findById(applied.getId()).orElseThrow();
        assertThat(stuck.getStatus()).isEqualTo(LoanStatus.DISBURSING);

        // Recovery asks the ledger what actually happened. Here it did commit.
        when(accountServiceClient.entriesForReference(anyString()))
                .thenReturn(List.of(Map.of("entryRef", "LEDG-1", "direction", "CREDIT")));
        forceStale(applied.getId());

        assertThat(loanService.recoverStuckDisbursements()).isEqualTo(1);
        assertThat(loanRepository.findById(applied.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("Recovery rolls a loan back when the ledger never posted the credit")
    void recoveryRollsBackWhenTheLedgerHasNothing() {
        when(accountServiceClient.credit(any(MoneyMovementRequest.class), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));

        LoanResponse applied = loanService.apply(application(new BigDecimal("200000"), 12));
        loanService.approve(applied.getId(), decision("approved"));
        assertThatThrownBy(() -> loanService.disburse(applied.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);

        when(accountServiceClient.entriesForReference(anyString())).thenReturn(List.of());
        forceStale(applied.getId());

        assertThat(loanService.recoverStuckDisbursements()).isEqualTo(1);
        assertThat(loanRepository.findById(applied.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.APPROVED);
    }

    @Test
    @DisplayName("A repayment is collected and applied to the oldest instalment first")
    void appliesARepaymentToTheOldestInstalment() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);
        BigDecimal emi = disbursed.getEmiAmount();

        RepaymentRequest repayment = new RepaymentRequest();
        repayment.setAmount(emi);
        repayment.setIdempotencyKey("repay-1");

        LoanResponse afterPayment = loanService.repay(disbursed.getId(), repayment);

        assertThat(afterPayment.getTotalRepaid()).isEqualByComparingTo(emi);
        assertThat(afterPayment.getOutstandingPrincipal()).isLessThan(new BigDecimal("120000.00"));
        assertThat(loanService.getSchedule(disbursed.getId()).get(0).getStatus())
                .isEqualTo(RepaymentStatus.PAID);
        assertThat(loanService.getSchedule(disbursed.getId()).get(1).getStatus())
                .isEqualTo(RepaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Paying every instalment closes the loan")
    void closesAFullyRepaidLoan() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);

        BigDecimal total = loanService.getSchedule(disbursed.getId()).stream()
                .map(instalment -> instalment.getEmiAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RepaymentRequest settlement = new RepaymentRequest();
        settlement.setAmount(total);
        settlement.setIdempotencyKey("settle-in-full");

        LoanResponse closed = loanService.repay(disbursed.getId(), settlement);

        assertThat(closed.getStatus()).isEqualTo(LoanStatus.CLOSED);
        assertThat(closed.getOutstandingPrincipal()).isEqualByComparingTo("0.00");
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(loanService.getSchedule(disbursed.getId()))
                .allSatisfy(instalment -> assertThat(instalment.getStatus()).isEqualTo(RepaymentStatus.PAID));
    }

    @Test
    @DisplayName("Paying more than is owed is refused and the collected money is put back")
    void reversesAnOverpayment() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);

        BigDecimal total = loanService.getSchedule(disbursed.getId()).stream()
                .map(instalment -> instalment.getEmiAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RepaymentRequest overpayment = new RepaymentRequest();
        overpayment.setAmount(total.add(new BigDecimal("5000.00")));
        overpayment.setIdempotencyKey("overpay");

        assertThatThrownBy(() -> loanService.repay(disbursed.getId(), overpayment))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exceeds the outstanding balance");

        // Compensating action: the debit that had already been collected is reversed.
        verify(accountServiceClient, times(1)).reverse(anyString(), anyString(), any());
        assertThat(loanRepository.findById(disbursed.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("Automatic collection debits the account for an instalment that has fallen due")
    void collectsADueInstallment() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);
        makeFirstInstallmentDue(disbursed.getId());

        assertThat(loanService.collectDueInstallments()).isEqualTo(1);

        assertThat(loanService.getSchedule(disbursed.getId()).get(0).getStatus())
                .isEqualTo(RepaymentStatus.PAID);
        assertThat(loanService.getLoan(disbursed.getId()).getTotalRepaid())
                .isEqualByComparingTo(disbursed.getEmiAmount());
    }

    @Test
    @DisplayName("A second collection sweep does not debit the same instalment again")
    void collectionIsIdempotentAcrossSweeps() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);
        makeFirstInstallmentDue(disbursed.getId());

        loanService.collectDueInstallments();
        BigDecimal afterFirstSweep = loanService.getLoan(disbursed.getId()).getTotalRepaid();

        // Every instance runs this job, so a repeat must be harmless.
        loanService.collectDueInstallments();

        assertThat(loanService.getLoan(disbursed.getId()).getTotalRepaid())
                .isEqualByComparingTo(afterFirstSweep);
    }

    @Test
    @DisplayName("An instalment the customer cannot cover is skipped, not forced through")
    void skipsAnInstallmentThatCannotBeCovered() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);
        makeFirstInstallmentDue(disbursed.getId());

        when(accountServiceClient.debit(any(MoneyMovementRequest.class), anyString()))
                .thenThrow(new InsufficientFundsException("XXXX1234", new BigDecimal("10.00"),
                        new BigDecimal("11000.00")));

        assertThat(loanService.collectDueInstallments())
                .as("nothing collected, and no exception escapes the sweep")
                .isZero();
        assertThat(loanService.getSchedule(disbursed.getId()).get(0).getStatus())
                .isNotEqualTo(RepaymentStatus.PAID);
    }

    @Test
    @DisplayName("A loan unpaid for more than ninety days is written off as DEFAULTED")
    void writesOffALongOverdueLoan() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);
        jdbcTemplate.update("update loan_repayments set due_date = ? where loan_id = ? and installment_number = 1",
                java.sql.Date.valueOf(LocalDate.now().minusDays(120)), disbursed.getId());

        assertThat(loanService.markDefaultedLoans()).isEqualTo(1);
        assertThat(loanService.getLoan(disbursed.getId()).getStatus()).isEqualTo(LoanStatus.DEFAULTED);

        // A defaulted loan takes no further automatic collection.
        assertThat(loanService.collectDueInstallments()).isZero();
    }

    @Test
    @DisplayName("A loan only slightly overdue is not written off")
    void doesNotWriteOffARecentlyMissedInstallment() {
        LoanResponse disbursed = disburseLoan(new BigDecimal("120000"), 12);
        jdbcTemplate.update("update loan_repayments set due_date = ? where loan_id = ? and installment_number = 1",
                java.sql.Date.valueOf(LocalDate.now().minusDays(10)), disbursed.getId());

        assertThat(loanService.markDefaultedLoans()).isZero();
        assertThat(loanService.getLoan(disbursed.getId()).getStatus()).isEqualTo(LoanStatus.DISBURSED);
    }

    /** Back-dates the first instalment so the collection sweep considers it due. */
    private void makeFirstInstallmentDue(Long loanId) {
        jdbcTemplate.update("update loan_repayments set due_date = ? where loan_id = ? and installment_number = 1",
                java.sql.Date.valueOf(LocalDate.now()), loanId);
    }

    private LoanResponse disburseLoan(BigDecimal principal, int tenureMonths) {
        LoanResponse applied = loanService.apply(application(principal, tenureMonths));
        loanService.approve(applied.getId(), decision("approved"));
        return loanService.disburse(applied.getId());
    }

    /**
     * Ages the disbursement so the recovery sweep considers it stale.
     *
     * <p>Back-dating works because {@code disbursementStartedAt} is a plain column. {@code updatedAt}
     * could not be used: the entity callback rewrites it on every save.</p>
     */
    private void forceStale(Long loanId) {
        var loan = loanRepository.findById(loanId).orElseThrow();
        loan.setDisbursementStartedAt(java.time.LocalDateTime.now().minusMinutes(10));
        loanRepository.saveAndFlush(loan);
    }

    private LoanApplicationRequest application(BigDecimal principal, int tenureMonths) {
        LoanApplicationRequest request = new LoanApplicationRequest();
        request.setCustomerId(1L);
        request.setDisbursementAccountId(10L);
        request.setLoanType(LoanType.PERSONAL);
        request.setPrincipal(principal);
        request.setTenureMonths(tenureMonths);
        request.setCurrency("INR");
        return request;
    }

    private LoanDecisionRequest decision(String reason) {
        LoanDecisionRequest request = new LoanDecisionRequest();
        request.setReason(reason);
        return request;
    }

    private CustomerProfile creditworthyProfile() {
        CustomerProfile profile = new CustomerProfile();
        profile.setCustomerId(1L);
        profile.setFullName("Test Customer");
        profile.setKycStatus("VERIFIED");
        profile.setKycVerified(true);
        profile.setStatus("ACTIVE");
        profile.setActive(true);
        profile.setAnnualIncome(new BigDecimal("1200000"));
        profile.setCreditScore(780);
        profile.setDateOfBirth(LocalDate.now().minusYears(35));
        return profile;
    }

    private MoneyMovementResponse posted(String balanceAfter) {
        return MoneyMovementResponse.builder()
                .reference("LEDG-REF")
                .status("POSTED")
                .accountId(10L)
                .balanceAfter(new BigDecimal(balanceAfter))
                .build();
    }
}
