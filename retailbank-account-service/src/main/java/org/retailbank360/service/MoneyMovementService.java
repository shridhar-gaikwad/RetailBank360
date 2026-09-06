package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.common.idempotency.IdempotencyService;
import org.retailbank360.common.lock.LockHandle;
import org.retailbank360.common.lock.LockRequest;
import org.retailbank360.common.lock.LockResourceTypes;
import org.retailbank360.common.lock.LockTemplate;
import org.retailbank360.common.util.IdGenerator;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Locking, idempotency and retry around every balance change.
 *
 * <p>The order of the three wrappers is deliberate and is the heart of the locking feature:</p>
 * <ol>
 *   <li><b>Idempotency outermost.</b> A replay of the same client key returns the stored result
 *       without taking any lock at all, so a retrying client cannot queue behind itself.</li>
 *   <li><b>Resource lock next.</b> Acquired before the transaction opens, so a caller waiting for a
 *       busy account is not holding a database connection while it waits. All accounts involved are
 *       locked together and in sorted key order, which is what makes concurrent A-to-B and B-to-A
 *       transfers serialise instead of deadlocking.</li>
 *   <li><b>Transaction innermost</b>, in {@link MoneyMovementTxService}, where the rows are locked
 *       with {@code SELECT ... FOR UPDATE} and the balances and ledger entries are written together.</li>
 * </ol>
 *
 * <p>Between the second and third layer sits a bounded retry: if the database still reports a
 * deadlock or a lost optimistic-lock race, the section is re-run with exponential back-off. That is
 * only safe because layer one guarantees the work is idempotent.</p>
 */
@Slf4j
@Service
public class MoneyMovementService {

    private static final String SCOPE_TRANSFER = "TRANSFER";
    private static final String SCOPE_CREDIT = "CREDIT";
    private static final String SCOPE_DEBIT = "DEBIT";

    private final MoneyMovementTxService txService;
    private final LockTemplate lockTemplate;
    private final IdempotencyService idempotencyService;
    private final AuditPublisher auditPublisher;

    public MoneyMovementService(MoneyMovementTxService txService,
                                LockTemplate lockTemplate,
                                IdempotencyService idempotencyService,
                                AuditPublisher auditPublisher) {
        this.txService = txService;
        this.lockTemplate = lockTemplate;
        this.idempotencyService = idempotencyService;
        this.auditPublisher = auditPublisher;
    }

    /** Debit one account, credit another, atomically and exactly once per idempotency key. */
    public MoneyMovementResponse transfer(TransferInstruction instruction, String idempotencyKey) {
        String operationId = operationId(instruction.getOperationId(), instruction.getReference());

        return idempotencyService.execute(SCOPE_TRANSFER, idempotencyKey, instruction,
                MoneyMovementResponse.class,
                () -> {
                    try {
                        MoneyMovementResponse response = lockTemplate.executeWithLocksAndRetry(
                                "transfer " + instruction.getReference(),
                                List.of(
                                        LockRequest.of(LockResourceTypes.ACCOUNT,
                                                instruction.getFromAccountId(), operationId),
                                        LockRequest.of(LockResourceTypes.ACCOUNT,
                                                instruction.getToAccountId(), operationId)),
                                handles -> txService.transfer(instruction, fencingTokens(handles)));

                        auditPublisher.publish(AuditEventRequest.builder()
                                .action(AuditActions.TRANSFER_COMPLETED)
                                .entityType("ACCOUNT")
                                .entityId(String.valueOf(instruction.getFromAccountId()))
                                .operationId(operationId)
                                .build()
                                .with("reference", instruction.getReference())
                                .with("amount", instruction.getAmount())
                                .with("toAccountId", instruction.getToAccountId())
                                .with("fromBalanceAfter", response.getBalanceAfter()));
                        return response;
                    } catch (RuntimeException e) {
                        auditPublisher.publishFailure(AuditActions.TRANSFER_FAILED, "ACCOUNT",
                                instruction.getFromAccountId(), operationId, e.getMessage());
                        throw e;
                    }
                });
    }

    /** Add money to one account. */
    public MoneyMovementResponse credit(MoneyMovementRequest request, String idempotencyKey) {
        String operationId = operationId(request.getOperationId(), request.getReference());

        return idempotencyService.execute(SCOPE_CREDIT, idempotencyKey, request, MoneyMovementResponse.class,
                () -> {
                    MoneyMovementResponse response = lockTemplate.executeWithLockAndRetry(
                            "credit " + request.getReference(),
                            LockRequest.of(LockResourceTypes.ACCOUNT, request.getAccountId(), operationId),
                            handle -> txService.credit(request, handle.fencingToken()));

                    auditPublisher.publish(AuditEventRequest.builder()
                            .action(AuditActions.ACCOUNT_CREDITED)
                            .entityType("ACCOUNT")
                            .entityId(String.valueOf(request.getAccountId()))
                            .operationId(operationId)
                            .build()
                            .with("reference", request.getReference())
                            .with("amount", request.getAmount())
                            .with("balanceAfter", response.getBalanceAfter()));
                    return response;
                });
    }

    /** Take money out of one account. */
    public MoneyMovementResponse debit(MoneyMovementRequest request, String idempotencyKey) {
        String operationId = operationId(request.getOperationId(), request.getReference());

        return idempotencyService.execute(SCOPE_DEBIT, idempotencyKey, request, MoneyMovementResponse.class,
                () -> {
                    MoneyMovementResponse response = lockTemplate.executeWithLockAndRetry(
                            "debit " + request.getReference(),
                            LockRequest.of(LockResourceTypes.ACCOUNT, request.getAccountId(), operationId),
                            handle -> txService.debit(request, handle.fencingToken()));

                    auditPublisher.publish(AuditEventRequest.builder()
                            .action(AuditActions.ACCOUNT_DEBITED)
                            .entityType("ACCOUNT")
                            .entityId(String.valueOf(request.getAccountId()))
                            .operationId(operationId)
                            .build()
                            .with("reference", request.getReference())
                            .with("amount", request.getAmount())
                            .with("balanceAfter", response.getBalanceAfter()));
                    return response;
                });
    }

    /**
     * Compensating action for a saga: undoes everything posted under {@code reference}.
     *
     * <p>Called by transaction-service or loan-service when a later step of their workflow failed
     * after the money had already moved.</p>
     */
    public MoneyMovementResponse reverse(String reference, String reason, List<Long> accountIds) {
        String reversalReference = IdGenerator.reference("REV");
        String operationId = "reversal-" + reference;

        List<LockRequest> locks = accountIds.stream()
                .map(accountId -> LockRequest.of(LockResourceTypes.ACCOUNT, accountId, operationId))
                .toList();

        MoneyMovementResponse response = lockTemplate.executeWithLocksAndRetry(
                "reverse " + reference, locks,
                handles -> txService.reverse(reference, reversalReference, reason, fencingTokens(handles)));

        log.warn("Reversed reference {} as {} ({})", reference, reversalReference, reason);
        auditPublisher.publish(AuditEventRequest.builder()
                .action(AuditActions.TRANSFER_REVERSED)
                .entityType("LEDGER")
                .entityId(reference)
                .operationId(operationId)
                .build()
                .with("reversalReference", reversalReference)
                .with("reason", reason));
        return response;
    }

    /** Maps each held lock onto the account it protects, so the ledger can stamp the right token. */
    private static Map<Long, Long> fencingTokens(List<LockHandle> handles) {
        Map<Long, Long> tokens = new HashMap<>();
        for (LockHandle handle : handles) {
            if (LockResourceTypes.ACCOUNT.equals(handle.resourceType())) {
                tokens.put(Long.valueOf(handle.resourceId()), handle.fencingToken());
            }
        }
        return tokens;
    }

    private static String operationId(String supplied, String reference) {
        return supplied == null || supplied.isBlank() ? reference : supplied;
    }
}
