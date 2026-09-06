package org.retailbank360.service;

import org.retailbank360.dto.EligibilityResponse;
import org.retailbank360.dto.LoanApplicationRequest;
import org.retailbank360.dto.LoanDecisionRequest;
import org.retailbank360.dto.LoanResponse;
import org.retailbank360.dto.RepaymentRequest;
import org.retailbank360.dto.RepaymentScheduleResponse;

import java.util.List;

/** Loan origination and servicing. */
public interface LoanService {

    /** Scores an application without committing to it, so a customer can check before applying. */
    EligibilityResponse checkEligibility(LoanApplicationRequest request);

    /** Submits an application. An application failing the rules is recorded as REJECTED, not lost. */
    LoanResponse apply(LoanApplicationRequest request);

    LoanResponse approve(Long loanId, LoanDecisionRequest request);

    LoanResponse reject(Long loanId, LoanDecisionRequest request);

    /**
     * Funds an approved loan: builds the schedule and credits the customer's account.
     *
     * <p>Runs under a lock on both the loan and the account, so two officers cannot fund the same
     * loan twice and a disbursement cannot interleave with a transfer on the same account.</p>
     */
    LoanResponse disburse(Long loanId);

    /** Collects a payment from the account and applies it to the schedule, oldest instalment first. */
    LoanResponse repay(Long loanId, RepaymentRequest request);

    LoanResponse getLoan(Long loanId);

    LoanResponse getLoanByRef(String loanRef);

    /** Bounded listing; the loan book is not something to serialise in one response. */
    List<LoanResponse> getAllLoans(int page, int size);

    List<LoanResponse> getLoansByCustomer(Long customerId);

    List<RepaymentScheduleResponse> getSchedule(Long loanId);

    /**
     * Settles loans left in {@code DISBURSING} because the ledger outcome was never observed.
     *
     * @return number of loans settled
     */
    int recoverStuckDisbursements();

    /** Marks instalments that are past their due date. */
    int markOverdueInstallments();

    /**
     * Collects instalments that have fallen due, by debiting the loan's account.
     *
     * <p>An instalment the customer cannot cover is left alone rather than retried into an overdraft;
     * it simply stays outstanding and is marked overdue.</p>
     *
     * @return number of instalments successfully collected
     */
    int collectDueInstallments();

    /**
     * Writes off loans whose oldest unpaid instalment is more than 90 days past due.
     *
     * @return number of loans moved to DEFAULTED
     */
    int markDefaultedLoans();
}
