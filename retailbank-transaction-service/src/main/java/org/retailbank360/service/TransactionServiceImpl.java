package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.client.AccountServiceClient;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.common.exception.BusinessException;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.idempotency.IdempotencyService;
import org.retailbank360.common.notification.NotificationPublisher;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.constants.TransactionStatus;
import org.retailbank360.constants.TransactionType;
import org.retailbank360.dto.DepositRequest;
import org.retailbank360.dto.TransactionResponse;
import org.retailbank360.dto.TransferRequest;
import org.retailbank360.dto.WithdrawRequest;
import org.retailbank360.entity.Transaction;
import org.retailbank360.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Orchestrates deposits, withdrawals and transfers as sagas.
 *
 * <h2>The saga, step by step</h2>
 * <ol>
 *   <li>Reserve the client's idempotency key, so a retry can never post twice.</li>
 *   <li>Write a {@code PENDING} transaction record in its own transaction, so intent is durable
 *       before any money moves. If this service dies now, the record is a recoverable loose end
 *       rather than a silent gap.</li>
 *   <li>Call the internal ledger API. That call <em>is</em> the atomic step: account-service owns
 *       both balances and commits both legs in one local transaction, so there is no half-transfer
 *       to compensate for.</li>
 *   <li>Record the outcome. A business rejection marks the record {@code FAILED}. An <em>ambiguous</em>
 *       outcome - a timeout, a dropped connection - is deliberately left {@code PENDING} and settled
 *       by asking the ledger what actually happened, because guessing either way risks either a lost
 *       transfer or a double posting.</li>
 * </ol>
 *
 * <p>Compensation exists for the case the saga cannot avoid: a movement that succeeded but must be
 * undone. {@link #reverse} asks the ledger to write mirror-image entries; nothing is ever deleted.</p>
 */
@Slf4j
@Service
public class TransactionServiceImpl implements TransactionService {

    private static final String SCOPE = "TRANSACTION";

    /** A PENDING record older than this had its outcome lost and needs reconciling. */
    private static final int STALE_PENDING_MINUTES = 2;

    private final TransactionRepository transactionRepository;
    private final TransactionRecordService recordService;
    private final AccountServiceClient accountClient;
    private final IdempotencyService idempotencyService;
    private final AuditPublisher auditPublisher;
    private final NotificationPublisher notificationPublisher;

    public TransactionServiceImpl(TransactionRepository transactionRepository,
                                  TransactionRecordService recordService,
                                  AccountServiceClient accountClient,
                                  IdempotencyService idempotencyService,
                                  AuditPublisher auditPublisher,
                                  NotificationPublisher notificationPublisher) {
        this.transactionRepository = transactionRepository;
        this.recordService = recordService;
        this.accountClient = accountClient;
        this.idempotencyService = idempotencyService;
        this.auditPublisher = auditPublisher;
        this.notificationPublisher = notificationPublisher;
    }

    @Override
    public TransactionResponse deposit(DepositRequest request) {
        String key = idempotencyKey(request.getIdempotencyKey());

        return idempotencyService.execute(SCOPE, key, request, TransactionResponse.class, () -> {
            Long owner = resolveOwner(request.getAccountId());
            Transaction transaction = recordService.createPending(
                    TransactionType.DEPOSIT, null, request.getAccountId(),
                    request.getAmount(), request.getCurrency(), request.getDescription(), key, owner);

            return runLedgerStep(transaction, ref -> accountClient.credit(MoneyMovementRequest.builder()
                    .accountId(request.getAccountId())
                    .amount(MoneyUtil.normalize(request.getAmount()))
                    .currency(request.getCurrency())
                    .movementType("DEPOSIT")
                    .reference(ref)
                    .operationId(ref)
                    .description(request.getDescription())
                    .initiatedBy(SecurityUtils.currentUsername())
                    .build(), key));
        });
    }

    @Override
    public TransactionResponse withdraw(WithdrawRequest request) {
        String key = idempotencyKey(request.getIdempotencyKey());

        return idempotencyService.execute(SCOPE, key, request, TransactionResponse.class, () -> {
            Long owner = resolveOwner(request.getAccountId());
            // A customer may only withdraw from their own account; staff act on anyone's.
            SecurityUtils.requireCustomerAccess(owner);

            Transaction transaction = recordService.createPending(
                    TransactionType.WITHDRAWAL, request.getAccountId(), null,
                    request.getAmount(), request.getCurrency(), request.getDescription(), key, owner);

            return runLedgerStep(transaction, ref -> accountClient.debit(MoneyMovementRequest.builder()
                    .accountId(request.getAccountId())
                    .amount(MoneyUtil.normalize(request.getAmount()))
                    .currency(request.getCurrency())
                    .movementType("WITHDRAWAL")
                    .reference(ref)
                    .operationId(ref)
                    .description(request.getDescription())
                    .initiatedBy(SecurityUtils.currentUsername())
                    .build(), key));
        });
    }

    @Override
    public TransactionResponse transfer(TransferRequest request) {
        String key = idempotencyKey(request.getIdempotencyKey());

        return idempotencyService.execute(SCOPE, key, request, TransactionResponse.class, () -> {
            Long owner = resolveOwner(request.getFromAccountId());
            // Money may only be pushed out of an account the caller owns.
            SecurityUtils.requireCustomerAccess(owner);

            Transaction transaction = recordService.createPending(
                    TransactionType.TRANSFER, request.getFromAccountId(), request.getToAccountId(),
                    request.getAmount(), request.getCurrency(), request.getDescription(), key, owner);

            return runLedgerStep(transaction, ref -> accountClient.transfer(TransferInstruction.builder()
                    .fromAccountId(request.getFromAccountId())
                    .toAccountId(request.getToAccountId())
                    .amount(MoneyUtil.normalize(request.getAmount()))
                    .currency(request.getCurrency())
                    .reference(ref)
                    .operationId(ref)
                    .description(request.getDescription())
                    .initiatedBy(SecurityUtils.currentUsername())
                    .build(), key));
        });
    }

    /**
     * Invokes the ledger and records what came back.
     *
     * <p>The three outcomes are handled differently on purpose. A business rejection is final and is
     * recorded as {@code FAILED}. A success is recorded with the ledger entry references. An
     * infrastructure failure is <em>ambiguous</em>: the movement may well have committed on the other
     * side, so the record stays {@code PENDING} for reconciliation rather than being guessed at.</p>
     */
    private TransactionResponse runLedgerStep(Transaction transaction,
                                              Function<String, MoneyMovementResponse> ledgerCall) {
        String reference = transaction.getTransactionRef();
        try {
            MoneyMovementResponse movement = ledgerCall.apply(reference);
            Transaction settled = recordService.markSuccess(reference, movement);

            auditPublisher.publish(AuditEventRequest.builder()
                    .action(AuditActions.TRANSACTION_COMPLETED)
                    .entityType("TRANSACTION")
                    .entityId(reference)
                    .operationId(reference)
                    .build()
                    .with("type", settled.getTransactionType())
                    .with("amount", settled.getAmount()));

            notifyCustomer(settled);
            return TransactionResponse.from(settled);

        } catch (BusinessException e) {
            // The ledger refused: insufficient funds, closed account, limit breached. Nothing moved.
            recordService.markFailed(reference, e.getErrorCode() + ": " + e.getMessage());
            auditPublisher.publishFailure(AuditActions.TRANSACTION_FAILED, "TRANSACTION", reference,
                    reference, e.getMessage());
            log.warn("Transaction {} rejected by the ledger: {}", reference, e.getMessage());
            throw e;

        } catch (RuntimeException e) {
            log.error("Transaction {} has an unknown outcome and stays PENDING for reconciliation", reference, e);
            auditPublisher.publishFailure(AuditActions.TRANSACTION_FAILED, "TRANSACTION", reference,
                    reference, "Unknown outcome: " + e.getMessage());
            throw new BusinessRuleViolationException("LEDGER_OUTCOME_UNKNOWN",
                    "The ledger did not confirm transaction " + reference
                            + ". It will be reconciled automatically; query it before retrying.");
        }
    }

    /** Tells the customer money moved. Best effort: never allowed to affect the transaction. */
    private void notifyCustomer(Transaction transaction) {
        String verb = switch (transaction.getTransactionType()) {
            case DEPOSIT -> "credited to";
            case WITHDRAWAL -> "debited from";
            case TRANSFER -> "transferred from";
        };
        notificationPublisher.notifyCustomer(
                transaction.getOwnerCustomerId(),
                "TRANSACTION_" + transaction.getTransactionType(),
                transaction.getTransactionRef(),
                transaction.getTransactionType() + " of " + transaction.getAmount() + " "
                        + transaction.getCurrency(),
                transaction.getAmount() + " " + transaction.getCurrency() + " was " + verb
                        + " your account. Reference " + transaction.getTransactionRef()
                        + ". Balance after: " + transaction.getBalanceAfter() + ".");
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String transactionRef) {
        Transaction transaction = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException("No transaction with reference " + transactionRef));
        SecurityUtils.requireCustomerAccess(transaction.getOwnerCustomerId());
        return TransactionResponse.from(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionResponse> getAllTransactions(int page, int size) {
        return transactionRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(TransactionResponse::from)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactionsByAccount(Long accountId, int page, int size) {
        SecurityUtils.requireCustomerAccess(resolveOwner(accountId));
        return transactionRepository.findByAccount(accountId, PageRequest.of(page, size))
                .map(TransactionResponse::from)
                .getContent();
    }

    @Override
    public TransactionResponse reverse(String transactionRef, String reason) {
        Transaction transaction = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException("No transaction with reference " + transactionRef));

        if (transaction.getStatus() != TransactionStatus.SUCCESS) {
            throw new BusinessRuleViolationException(
                    "Only a SUCCESS transaction can be reversed; " + transactionRef
                            + " is " + transaction.getStatus());
        }

        List<Long> accountIds = Stream
                .of(transaction.getFromAccountId(), transaction.getToAccountId())
                .filter(Objects::nonNull)
                .toList();

        MoneyMovementResponse reversal = accountClient.reverse(transactionRef, reason, accountIds);
        Transaction reversed = recordService.markReversed(transactionRef, reversal.getReference(), reason);

        auditPublisher.publish(AuditEventRequest.builder()
                .action(AuditActions.TRANSACTION_COMPENSATED)
                .entityType("TRANSACTION")
                .entityId(transactionRef)
                .operationId(transactionRef)
                .build()
                .with("reversalReference", reversal.getReference())
                .with("reason", reason));

        log.warn("Transaction {} reversed as {} ({})", transactionRef, reversal.getReference(), reason);
        return TransactionResponse.from(reversed);
    }

    /**
     * Settles transactions whose ledger outcome was never observed.
     *
     * <p>The ledger is the only authority on whether the money moved, so the job asks it. Entries
     * present means the movement committed and the record becomes {@code SUCCESS}; no entries after
     * the grace period means it never happened and the record becomes {@code FAILED}. Either way the
     * decision is read from the ledger, never inferred.</p>
     */
    @Override
    public int reconcileStalePending() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_PENDING_MINUTES);
        List<Transaction> stale = transactionRepository.findStalePending(cutoff);
        if (stale.isEmpty()) {
            return 0;
        }

        int settled = 0;
        for (Transaction transaction : stale) {
            String reference = transaction.getTransactionRef();
            try {
                List<Map<String, Object>> entries = accountClient.entriesForReference(reference);
                if (entries != null && !entries.isEmpty()) {
                    recordService.markSuccessFromReconciliation(reference, entries);
                    log.warn("Reconciled {}: the ledger had committed it after all", reference);
                } else {
                    recordService.markFailed(reference, "Reconciled: the ledger holds no entries for it");
                    log.warn("Reconciled {}: the ledger never posted it", reference);
                }
                settled++;
            } catch (ResourceNotFoundException e) {
                recordService.markFailed(reference, "Reconciled: the ledger holds no entries for it");
                settled++;
            } catch (RuntimeException e) {
                log.error("Could not reconcile {}; leaving it PENDING for the next sweep", reference, e);
            }
        }
        return settled;
    }

    /** Asks account-service who owns an account, so reads and debits can be authorised per row. */
    private Long resolveOwner(Long accountId) {
        Map<String, Object> summary = accountClient.accountSummary(accountId);
        if (summary == null || summary.get("customerId") == null) {
            throw new ResourceNotFoundException("Account", accountId);
        }
        return Long.valueOf(String.valueOf(summary.get("customerId")));
    }

    private static String idempotencyKey(String supplied) {
        return supplied == null || supplied.isBlank() ? IdGenerator.token() : supplied;
    }
}
