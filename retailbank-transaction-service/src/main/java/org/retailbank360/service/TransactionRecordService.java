package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.constants.TransactionType;
import org.retailbank360.entity.Transaction;
import org.retailbank360.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Durable state transitions of the transaction saga.
 *
 * <p>Every method commits in its own {@code REQUIRES_NEW} transaction. That is the property the saga
 * depends on: the {@code PENDING} record must be on disk <em>before</em> the ledger is called, and
 * the {@code FAILED} outcome must survive even though the exception that caused it is about to
 * propagate and roll the caller's transaction back. Without this, a crash between "intent" and
 * "outcome" would leave nothing to reconcile from.</p>
 */
@Slf4j
@Service
public class TransactionRecordService {

    private final TransactionRepository transactionRepository;

    public TransactionRecordService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /** Records intent before any money is asked to move. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction createPending(TransactionType type, Long fromAccountId, Long toAccountId,
                                     BigDecimal amount, String currency, String description,
                                     String idempotencyKey, Long ownerCustomerId) {
        Transaction transaction = new Transaction();
        transaction.setTransactionRef(IdGenerator.reference("TXN"));
        transaction.setTransactionType(type);
        transaction.setFromAccountId(fromAccountId);
        transaction.setToAccountId(toAccountId);
        transaction.setAmount(MoneyUtil.normalize(amount));
        transaction.setCurrency(currency == null || currency.isBlank() ? "INR" : currency.toUpperCase());
        transaction.setDescription(description);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setOwnerCustomerId(ownerCustomerId);
        transaction.setInitiatedBy(SecurityUtils.currentUsername());

        Transaction saved = transactionRepository.save(transaction);
        log.info("Recorded {} {} for {} {} as PENDING", type, saved.getTransactionRef(),
                saved.getAmount(), saved.getCurrency());
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction markSuccess(String transactionRef, MoneyMovementResponse movement) {
        Transaction transaction = require(transactionRef);
        transaction.markSuccess(movement.getDebitEntryRef(), movement.getCreditEntryRef(),
                movement.getBalanceAfter());
        return transactionRepository.save(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction markFailed(String transactionRef, String reason) {
        Transaction transaction = require(transactionRef);
        transaction.markFailed(reason);
        return transactionRepository.save(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction markReversed(String transactionRef, String reversalReference, String reason) {
        Transaction transaction = require(transactionRef);
        transaction.markReversed(reversalReference, reason);
        return transactionRepository.save(transaction);
    }

    /**
     * Closes out a transaction whose outcome was learned from the ledger rather than from the
     * original call, filling in the entry references the lost response would have carried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction markSuccessFromReconciliation(String transactionRef, List<Map<String, Object>> entries) {
        Transaction transaction = require(transactionRef);

        String debitRef = entryRefFor(entries, "DEBIT");
        String creditRef = entryRefFor(entries, "CREDIT");
        BigDecimal balanceAfter = entries.stream()
                .filter(entry -> matchesOwnSide(transaction, entry))
                .map(entry -> new BigDecimal(String.valueOf(entry.get("balanceAfter"))))
                .findFirst()
                .orElse(null);

        transaction.markSuccess(debitRef, creditRef, balanceAfter);
        transaction.setFailureReason("Settled by reconciliation against the ledger");
        return transactionRepository.save(transaction);
    }

    private static String entryRefFor(List<Map<String, Object>> entries, String direction) {
        return entries.stream()
                .filter(entry -> direction.equals(String.valueOf(entry.get("direction"))))
                .map(entry -> String.valueOf(entry.get("entryRef")))
                .findFirst()
                .orElse(null);
    }

    /** The leg belonging to the account the customer acted on, for the balance we quote back. */
    private static boolean matchesOwnSide(Transaction transaction, Map<String, Object> entry) {
        String direction = String.valueOf(entry.get("direction"));
        return transaction.getFromAccountId() != null ? "DEBIT".equals(direction) : "CREDIT".equals(direction);
    }

    private Transaction require(String transactionRef) {
        return transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No transaction with reference " + transactionRef));
    }
}
