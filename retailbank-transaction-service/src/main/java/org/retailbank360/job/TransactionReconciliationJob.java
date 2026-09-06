package org.retailbank360.job;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.service.TransactionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes out transactions whose ledger outcome was never observed.
 *
 * <p>This is the recovery arm of the saga. A crash or a dropped response between "the ledger was
 * called" and "the answer came back" leaves a PENDING row; the job asks account-service what
 * actually happened and settles it either way. Without this, an ambiguous transfer would sit
 * unresolved forever.</p>
 *
 * <p>Safe to run on every instance: the reconciliation reads the ledger and writes a terminal state,
 * so a second instance simply finds nothing left to do.</p>
 */
@Slf4j
@Component
public class TransactionReconciliationJob {

    private final TransactionService transactionService;

    public TransactionReconciliationJob(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void reconcile() {
        try {
            int settled = transactionService.reconcileStalePending();
            if (settled > 0) {
                log.warn("Reconciliation settled {} transaction(s) with an unknown outcome", settled);
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation sweep failed; will retry on the next tick", e);
        }
    }
}
