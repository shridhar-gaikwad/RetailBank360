package org.retailbank360.job;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.service.LoanService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled loan servicing.
 *
 * <p>Two jobs, both idempotent so they are safe to run on every instance:</p>
 * <ul>
 *   <li>recovering loans left mid-disbursement, by asking the ledger what actually happened;</li>
 *   <li>collecting instalments that have fallen due;</li>
 *   <li>marking instalments that have passed their due date;</li>
 *   <li>writing off loans that have been unpaid for more than ninety days.</li>
 * </ul>
 *
 * <p>The daily jobs run in a deliberate order a few minutes apart: mark overdue first so the picture
 * is accurate, then collect, then write off what collection could not recover.</p>
 */
@Slf4j
@Component
public class LoanServicingJob {

    private final LoanService loanService;

    public LoanServicingJob(LoanService loanService) {
        this.loanService = loanService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void recoverStuckDisbursements() {
        try {
            int settled = loanService.recoverStuckDisbursements();
            if (settled > 0) {
                log.warn("Recovered {} loan disbursement(s) with an unknown outcome", settled);
            }
        } catch (RuntimeException e) {
            log.error("Disbursement recovery sweep failed; will retry on the next tick", e);
        }
    }

    /** Runs a few minutes after midnight, once the due date has actually rolled over. */
    @Scheduled(cron = "${retailbank.loan.overdue-cron:0 5 0 * * *}")
    public void markOverdueInstallments() {
        try {
            int marked = loanService.markOverdueInstallments();
            if (marked > 0) {
                log.info("Marked {} instalment(s) as OVERDUE", marked);
            }
        } catch (RuntimeException e) {
            log.error("Overdue marking failed; will retry tomorrow", e);
        }
    }

    /**
     * Automatic EMI collection.
     *
     * <p>Safe to run on every instance: each instalment is collected through the locked, idempotent
     * repayment path with a key derived from the loan and instalment number, so a second instance
     * finds the work already done rather than debiting twice.</p>
     */
    @Scheduled(cron = "${retailbank.loan.collection-cron:0 30 0 * * *}")
    public void collectDueInstallments() {
        try {
            int collected = loanService.collectDueInstallments();
            if (collected > 0) {
                log.info("Automatically collected {} instalment(s)", collected);
            }
        } catch (RuntimeException e) {
            log.error("Automatic collection failed; will retry tomorrow", e);
        }
    }

    /** Writes off loans that collection has failed to recover for more than ninety days. */
    @Scheduled(cron = "${retailbank.loan.default-cron:0 45 0 * * *}")
    public void markDefaultedLoans() {
        try {
            int defaulted = loanService.markDefaultedLoans();
            if (defaulted > 0) {
                log.warn("Wrote off {} loan(s) as DEFAULTED", defaulted);
            }
        } catch (RuntimeException e) {
            log.error("Default marking failed; will retry tomorrow", e);
        }
    }
}
