package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.InsufficientFundsException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.constants.LedgerDirection;
import org.retailbank360.constants.MovementType;
import org.retailbank360.entity.Account;
import org.retailbank360.entity.LedgerEntry;
import org.retailbank360.repository.AccountRepository;
import org.retailbank360.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The transactional core of money movement. Every balance change in the system goes through here.
 *
 * <h2>Why a transfer really is ACID</h2>
 * Both accounts are rows in this service's own database, so the debit and the credit are applied in
 * a single local transaction: either both land or neither does, with no compensation required. The
 * rows are read with {@code SELECT ... FOR UPDATE} in ascending id order - the same order for every
 * transaction, which removes the classic A-to-B / B-to-A deadlock - and the ledger entries are
 * written in the same transaction as the balances they describe.
 *
 * <h2>Why this is a separate bean</h2>
 * The distributed lock has to be acquired <em>before</em> the transaction opens, so the class that
 * takes the lock cannot be the same one that carries {@code @Transactional}: a self-invocation would
 * bypass the proxy and run the whole critical section outside a transaction. {@code
 * MoneyMovementService} owns the locking; this class owns the transaction.
 */
@Slf4j
@Service
public class MoneyMovementTxService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;

    public MoneyMovementTxService(AccountRepository accountRepository, LedgerEntryRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Debits one account and credits another atomically.
     *
     * @param fencingTokens fencing token per account id, from the locks held by the caller
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResponse transfer(TransferInstruction instruction, Map<Long, Long> fencingTokens) {
        BigDecimal amount = MoneyUtil.normalize(instruction.getAmount());
        requirePositive(amount);

        if (instruction.getFromAccountId().equals(instruction.getToAccountId())) {
            throw new BusinessRuleViolationException("A transfer must have two different accounts");
        }

        // Ascending id order: identical for every transaction, so no lock cycle can form.
        List<Long> ids = new ArrayList<>(List.of(instruction.getFromAccountId(), instruction.getToAccountId()));
        ids.sort(Long::compareTo);
        Map<Long, Account> locked = lockAccounts(ids);

        Account source = locked.get(instruction.getFromAccountId());
        Account target = locked.get(instruction.getToAccountId());

        // Replay guard at the ledger level, independent of the idempotency store: if this reference
        // already produced entries, the work is done and must not be repeated.
        if (ledgerRepository.existsByReferenceAndAccountId(instruction.getReference(), source.getId())) {
            log.info("Transfer {} was already posted; returning the stored outcome", instruction.getReference());
            return describeExisting(instruction.getReference(), source, target);
        }

        assertFencingToken(source, fencingTokens);
        assertFencingToken(target, fencingTokens);
        assertSameCurrency(source, target);
        assertDebitable(source);
        assertCreditable(target);
        assertSufficientFunds(source, amount, false);
        assertWithinDailyLimit(source, amount);

        LedgerEntry debit = applyMovement(source, amount, LedgerDirection.DEBIT, MovementType.TRANSFER_OUT,
                PostingDetails.of(instruction.getReference(), instruction.getOperationId(),
                        instruction.getDescription(), instruction.getInitiatedBy(), target.getId()),
                fencingTokens.get(source.getId()));
        LedgerEntry credit = applyMovement(target, amount, LedgerDirection.CREDIT, MovementType.TRANSFER_IN,
                PostingDetails.of(instruction.getReference(), instruction.getOperationId(),
                        instruction.getDescription(), instruction.getInitiatedBy(), source.getId()),
                fencingTokens.get(target.getId()));

        registerAgainstDailyLimit(source, amount);
        accountRepository.save(source);
        accountRepository.save(target);

        log.info("Transfer {} posted: {} {} from account {} to account {}",
                instruction.getReference(), amount, source.getCurrency(),
                MaskingUtil.maskAccountNumber(source.getAccountNumber()),
                MaskingUtil.maskAccountNumber(target.getAccountNumber()));

        return MoneyMovementResponse.builder()
                .reference(instruction.getReference())
                .status("POSTED")
                .accountId(source.getId())
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(source.getAccountNumber()))
                .amount(amount)
                .currency(source.getCurrency())
                .balanceAfter(source.getBalance())
                .debitEntryRef(debit.getEntryRef())
                .creditEntryRef(credit.getEntryRef())
                .counterpartyAccountId(target.getId())
                .counterpartyMaskedAccountNumber(MaskingUtil.maskAccountNumber(target.getAccountNumber()))
                .counterpartyBalanceAfter(target.getBalance())
                .fencingToken(fencingTokens.get(source.getId()))
                .postedAt(debit.getPostedAt())
                .build();
    }

    /** Adds money to one account: a deposit, a loan disbursement or an interest posting. */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResponse credit(MoneyMovementRequest request, long fencingToken) {
        BigDecimal amount = MoneyUtil.normalize(request.getAmount());
        requirePositive(amount);

        Account account = lockAccount(request.getAccountId());
        if (ledgerRepository.existsByReferenceAndAccountId(request.getReference(), account.getId())) {
            log.info("Credit {} was already posted; returning the stored outcome", request.getReference());
            return describeExisting(request.getReference(), account, null);
        }

        assertFencingToken(account, Map.of(account.getId(), fencingToken));
        assertCurrency(account, request.getCurrency());
        assertCreditable(account);

        MovementType movementType = resolveMovementType(request.getMovementType(), MovementType.DEPOSIT);
        LedgerEntry entry = applyMovement(account, amount, LedgerDirection.CREDIT, movementType,
                PostingDetails.of(request.getReference(), request.getOperationId(),
                        request.getDescription(), request.getInitiatedBy(), null),
                fencingToken);
        accountRepository.save(account);

        log.info("Credited {} {} to account {} ({})", amount, account.getCurrency(),
                MaskingUtil.maskAccountNumber(account.getAccountNumber()), request.getReference());

        return single(request.getReference(), account, amount, entry, null, fencingToken);
    }

    /** Takes money out of one account: a withdrawal, a fee or a loan repayment. */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResponse debit(MoneyMovementRequest request, long fencingToken) {
        BigDecimal amount = MoneyUtil.normalize(request.getAmount());
        requirePositive(amount);

        Account account = lockAccount(request.getAccountId());
        if (ledgerRepository.existsByReferenceAndAccountId(request.getReference(), account.getId())) {
            log.info("Debit {} was already posted; returning the stored outcome", request.getReference());
            return describeExisting(request.getReference(), account, null);
        }

        assertFencingToken(account, Map.of(account.getId(), fencingToken));
        assertCurrency(account, request.getCurrency());
        assertDebitable(account);
        assertSufficientFunds(account, amount, request.isBypassMinimumBalance());

        MovementType movementType = resolveMovementType(request.getMovementType(), MovementType.WITHDRAWAL);
        LedgerEntry entry = applyMovement(account, amount, LedgerDirection.DEBIT, movementType,
                PostingDetails.of(request.getReference(), request.getOperationId(),
                        request.getDescription(), request.getInitiatedBy(), null),
                fencingToken);
        accountRepository.save(account);

        log.info("Debited {} {} from account {} ({})", amount, account.getCurrency(),
                MaskingUtil.maskAccountNumber(account.getAccountNumber()), request.getReference());

        return single(request.getReference(), account, amount, entry, null, fencingToken);
    }

    /**
     * Writes compensating entries that undo every entry posted under {@code reference}.
     *
     * <p>This is the compensating action of the saga. Nothing is deleted: the original entries stay,
     * and each gets a mirror-image {@code REVERSAL} entry pointing back at it, so the ledger explains
     * both what happened and what was undone.</p>
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResponse reverse(String reference, String reversalReference, String reason,
                                         Map<Long, Long> fencingTokens) {
        List<LedgerEntry> original = ledgerRepository.findByReferenceOrderByPostedAtAsc(reference);
        if (original.isEmpty()) {
            throw new ResourceNotFoundException("No ledger entries found for reference " + reference);
        }
        if (original.get(0).getMovementType() == MovementType.REVERSAL) {
            throw new BusinessRuleViolationException(
                    "Reference " + reference + " is itself a reversal and cannot be reversed again");
        }
        // The compensating entries carry their own reference, so "has this been reversed?" is answered
        // by looking for entries that point back at these ones.
        List<String> originalRefs = original.stream().map(LedgerEntry::getEntryRef).toList();
        if (ledgerRepository.existsByReversesEntryRefIn(originalRefs)) {
            throw new BusinessRuleViolationException("Reference " + reference + " has already been reversed");
        }

        List<Long> ids = original.stream().map(LedgerEntry::getAccountId).distinct().sorted().toList();
        Map<Long, Account> locked = lockAccounts(ids);

        LedgerEntry lastReversal = null;
        for (LedgerEntry entry : original) {
            Account account = locked.get(entry.getAccountId());
            LedgerDirection opposite = entry.getDirection() == LedgerDirection.DEBIT
                    ? LedgerDirection.CREDIT : LedgerDirection.DEBIT;

            // A reversal restores the previous state, so it is allowed to breach the minimum balance
            // that the original movement respected; refusing it would leave the ledger unbalanced.
            // The link back to the original is set before the entry is written: once persisted a
            // ledger entry can never be touched again.
            lastReversal = applyMovement(account, entry.getAmount(), opposite, MovementType.REVERSAL,
                    PostingDetails.of(reversalReference, entry.getOperationId(),
                                    "Reversal of " + entry.getEntryRef() + ": " + reason,
                                    entry.getPostedBy(), entry.getCounterpartyAccountId())
                            .reversing(entry.getEntryRef()),
                    fencingTokens.getOrDefault(account.getId(), 0L));
            accountRepository.save(account);
        }
        ledgerRepository.flush();

        log.warn("Reversed {} ledger entry/entries for reference {} ({})", original.size(), reference, reason);
        Account primary = locked.get(original.get(0).getAccountId());
        return MoneyMovementResponse.builder()
                .reference(reversalReference)
                .status("REVERSED")
                .accountId(primary.getId())
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(primary.getAccountNumber()))
                .amount(original.get(0).getAmount())
                .currency(primary.getCurrency())
                .balanceAfter(primary.getBalance())
                .debitEntryRef(lastReversal == null ? null : lastReversal.getEntryRef())
                .postedAt(LocalDateTime.now())
                .build();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Descriptive fields of one posting.
     *
     * <p>Grouped into a record so {@code applyMovement} keeps a readable signature, and - more
     * importantly - so {@code reversesEntryRef} is set <em>before</em> the entry is persisted. A
     * ledger entry is immutable the moment it is written, so anything that must appear on it has to
     * be known up front.</p>
     */
    private record PostingDetails(String reference, String operationId, String description,
                                  String postedBy, Long counterpartyAccountId, String reversesEntryRef) {

        static PostingDetails of(String reference, String operationId, String description,
                                 String postedBy, Long counterpartyAccountId) {
            return new PostingDetails(reference, operationId, description, postedBy, counterpartyAccountId, null);
        }

        PostingDetails reversing(String originalEntryRef) {
            return new PostingDetails(reference, operationId, description, postedBy,
                    counterpartyAccountId, originalEntryRef);
        }
    }

    /** Applies one leg: writes the ledger entry and moves the balance, in that order. */
    private LedgerEntry applyMovement(Account account, BigDecimal amount, LedgerDirection direction,
                                      MovementType movementType, PostingDetails details, Long fencingToken) {

        BigDecimal newBalance = direction == LedgerDirection.CREDIT
                ? account.getBalance().add(amount)
                : account.getBalance().subtract(amount);

        account.setBalance(MoneyUtil.normalize(newBalance));
        if (fencingToken != null) {
            account.setLastFencingToken(fencingToken);
        }

        LedgerEntry entry = new LedgerEntry();
        entry.setEntryRef(IdGenerator.reference("LEDG"));
        entry.setAccountId(account.getId());
        entry.setAccountNumber(account.getAccountNumber());
        entry.setDirection(direction);
        entry.setMovementType(movementType);
        entry.setAmount(amount);
        entry.setCurrency(account.getCurrency());
        entry.setBalanceAfter(account.getBalance());
        entry.setCounterpartyAccountId(details.counterpartyAccountId());
        entry.setReference(details.reference());
        entry.setOperationId(details.operationId());
        entry.setDescription(details.description());
        entry.setPostedBy(details.postedBy());
        entry.setReversesEntryRef(details.reversesEntryRef());
        entry.setFencingToken(fencingToken);
        entry.setPostedAt(LocalDateTime.now());

        return ledgerRepository.save(entry);
    }

    private Account lockAccount(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private Map<Long, Account> lockAccounts(List<Long> ids) {
        List<Account> accounts = accountRepository.lockAllByIdOrdered(ids);
        if (accounts.size() != ids.size()) {
            List<Long> found = accounts.stream().map(Account::getId).toList();
            Long missing = ids.stream().filter(id -> !found.contains(id)).findFirst().orElse(null);
            throw new ResourceNotFoundException("Account", missing);
        }
        return accounts.stream().collect(java.util.stream.Collectors.toMap(Account::getId, account -> account));
    }

    private static void requirePositive(BigDecimal amount) {
        if (!MoneyUtil.isPositive(amount)) {
            throw new BusinessRuleViolationException("NON_POSITIVE_AMOUNT", "Amount must be greater than zero");
        }
    }

    /**
     * Refuses a writer whose lock has been taken over.
     *
     * <p>Each acquisition increases the fencing token of the resource, and every posting stamps the
     * token it ran under onto the account. A process that stalled, lost its lock to the TTL, and then
     * resumed will present a token lower than the one already recorded, and is rejected here rather
     * than being allowed to overwrite the newer holder's work.</p>
     */
    private static void assertFencingToken(Account account, Map<Long, Long> fencingTokens) {
        Long presented = fencingTokens.get(account.getId());
        Long recorded = account.getLastFencingToken();
        if (presented == null || recorded == null) {
            return;
        }
        if (presented < recorded) {
            throw new BusinessRuleViolationException("STALE_LOCK_TOKEN",
                    "This operation lost its lock on account " + account.getId()
                            + " and was superseded (token " + presented + " < " + recorded + "). Retry the request.");
        }
    }

    private static void assertDebitable(Account account) {
        if (!account.isDebitable()) {
            throw new BusinessRuleViolationException("ACCOUNT_NOT_DEBITABLE",
                    "Account " + MaskingUtil.maskAccountNumber(account.getAccountNumber())
                            + " is " + account.getStatus() + " and cannot be debited");
        }
    }

    private static void assertCreditable(Account account) {
        if (!account.isCreditable()) {
            throw new BusinessRuleViolationException("ACCOUNT_NOT_CREDITABLE",
                    "Account " + MaskingUtil.maskAccountNumber(account.getAccountNumber())
                            + " is " + account.getStatus() + " and cannot be credited");
        }
    }

    private static void assertSameCurrency(Account source, Account target) {
        if (!source.getCurrency().equals(target.getCurrency())) {
            throw new BusinessRuleViolationException("CURRENCY_MISMATCH",
                    "Cross-currency transfers are not supported: "
                            + source.getCurrency() + " to " + target.getCurrency());
        }
    }

    private static void assertCurrency(Account account, String requestedCurrency) {
        if (requestedCurrency != null && !requestedCurrency.isBlank()
                && !account.getCurrency().equalsIgnoreCase(requestedCurrency)) {
            throw new BusinessRuleViolationException("CURRENCY_MISMATCH",
                    "Account is held in " + account.getCurrency() + ", not " + requestedCurrency);
        }
    }

    /**
     * The core financial-correctness rule: a non-overdraft account may never fall below its minimum
     * balance, and an overdraft account may never fall below its negotiated limit.
     */
    private static void assertSufficientFunds(Account account, BigDecimal amount, boolean bypassMinimumBalance) {
        BigDecimal projected = account.getBalance().subtract(amount);
        BigDecimal floor = account.isOverdraftAllowed()
                ? account.getOverdraftLimit().negate()
                : (bypassMinimumBalance ? BigDecimal.ZERO : account.getMinimumBalance());

        if (MoneyUtil.lt(projected, floor)) {
            BigDecimal available = account.getBalance().subtract(floor).max(BigDecimal.ZERO);
            throw new InsufficientFundsException(
                    MaskingUtil.maskAccountNumber(account.getAccountNumber()), available, amount);
        }
    }

    /** Daily outbound ceiling, evaluated against today's running total. */
    private static void assertWithinDailyLimit(Account account, BigDecimal amount) {
        BigDecimal alreadyToday = account.getTransferredToday();
        BigDecimal projected = alreadyToday.add(amount);
        if (MoneyUtil.gt(projected, account.getDailyTransferLimit())) {
            throw new BusinessRuleViolationException("DAILY_TRANSFER_LIMIT_EXCEEDED",
                    "This transfer would take today's total to " + projected
                            + ", above the daily limit of " + account.getDailyTransferLimit()
                            + " (already transferred today: " + alreadyToday + ")");
        }
    }

    private static void registerAgainstDailyLimit(Account account, BigDecimal amount) {
        LocalDate today = LocalDate.now();
        if (!today.equals(account.getDailyLimitDate())) {
            account.setDailyLimitDate(today);
            account.setDailyTransferredAmount(BigDecimal.ZERO);
        }
        account.setDailyTransferredAmount(MoneyUtil.normalize(account.getDailyTransferredAmount().add(amount)));
    }

    private static MovementType resolveMovementType(String requested, MovementType fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        try {
            return MovementType.valueOf(requested.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("Unknown movement type: " + requested);
        }
    }

    private MoneyMovementResponse single(String reference, Account account, BigDecimal amount,
                                         LedgerEntry entry, Long counterpartyId, Long fencingToken) {
        boolean isDebit = entry.getDirection() == LedgerDirection.DEBIT;
        return MoneyMovementResponse.builder()
                .reference(reference)
                .status("POSTED")
                .accountId(account.getId())
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(account.getAccountNumber()))
                .amount(amount)
                .currency(account.getCurrency())
                .balanceAfter(account.getBalance())
                .debitEntryRef(isDebit ? entry.getEntryRef() : null)
                .creditEntryRef(isDebit ? null : entry.getEntryRef())
                .counterpartyAccountId(counterpartyId)
                .fencingToken(fencingToken)
                .postedAt(entry.getPostedAt())
                .build();
    }

    /** Rebuilds the outcome of a posting that already happened, so a replay is answered identically. */
    private MoneyMovementResponse describeExisting(String reference, Account account, Account counterparty) {
        List<LedgerEntry> entries = ledgerRepository.findByReferenceOrderByPostedAtAsc(reference);
        LedgerEntry own = entries.stream()
                .filter(entry -> entry.getAccountId().equals(account.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Ledger entry for reference " + reference));

        return MoneyMovementResponse.builder()
                .reference(reference)
                .status("ALREADY_POSTED")
                .accountId(account.getId())
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(account.getAccountNumber()))
                .amount(own.getAmount())
                .currency(own.getCurrency())
                .balanceAfter(account.getBalance())
                .debitEntryRef(own.getDirection() == LedgerDirection.DEBIT ? own.getEntryRef() : null)
                .creditEntryRef(own.getDirection() == LedgerDirection.CREDIT ? own.getEntryRef() : null)
                .counterpartyAccountId(counterparty == null ? null : counterparty.getId())
                .counterpartyBalanceAfter(counterparty == null ? null : counterparty.getBalance())
                .postedAt(own.getPostedAt())
                .build();
    }
}
