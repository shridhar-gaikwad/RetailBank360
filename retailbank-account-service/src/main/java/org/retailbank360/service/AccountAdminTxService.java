package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.dto.AccountLimitsRequest;
import org.retailbank360.dto.AccountResponse;
import org.retailbank360.dto.AccountStatusRequest;
import org.retailbank360.entity.Account;
import org.retailbank360.entity.AccountChangeHistory;
import org.retailbank360.repository.AccountChangeHistoryRepository;
import org.retailbank360.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional half of the administrative account operations.
 *
 * <p>Separate from {@code AccountServiceImpl} for the same reason as the KYC and money services: the
 * resource lock is taken outside the transaction, so the locking method and the transactional method
 * cannot live on the same proxied bean.</p>
 *
 * <p>Every field these methods change is also written to the append-only change history, which is
 * the "change history for critical operations" half of the audit requirement - the ledger already
 * covers balances, this covers who raised a limit or froze an account.</p>
 */
@Slf4j
@Service
public class AccountAdminTxService {

    private final AccountRepository accountRepository;
    private final AccountChangeHistoryRepository historyRepository;

    public AccountAdminTxService(AccountRepository accountRepository,
                                 AccountChangeHistoryRepository historyRepository) {
        this.accountRepository = accountRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public AccountResponse applyLimits(Long accountId, AccountLimitsRequest request) {
        Account account = lock(accountId);
        assertNotClosed(account);

        String actor = SecurityUtils.currentUsername();
        List<AccountChangeHistory> changes = new ArrayList<>();

        if (request.getMinimumBalance() != null
                && request.getMinimumBalance().compareTo(account.getMinimumBalance()) != 0) {
            changes.add(AccountChangeHistory.of(accountId, "minimumBalance",
                    account.getMinimumBalance(), request.getMinimumBalance(), actor, request.getReason()));
            account.setMinimumBalance(request.getMinimumBalance());
        }
        if (request.getOverdraftAllowed() != null && request.getOverdraftAllowed() != account.isOverdraftAllowed()) {
            changes.add(AccountChangeHistory.of(accountId, "overdraftAllowed",
                    account.isOverdraftAllowed(), request.getOverdraftAllowed(), actor, request.getReason()));
            account.setOverdraftAllowed(request.getOverdraftAllowed());
        }
        if (request.getOverdraftLimit() != null
                && request.getOverdraftLimit().compareTo(account.getOverdraftLimit()) != 0) {
            changes.add(AccountChangeHistory.of(accountId, "overdraftLimit",
                    account.getOverdraftLimit(), request.getOverdraftLimit(), actor, request.getReason()));
            account.setOverdraftLimit(request.getOverdraftLimit());
        }
        if (request.getDailyTransferLimit() != null
                && request.getDailyTransferLimit().compareTo(account.getDailyTransferLimit()) != 0) {
            changes.add(AccountChangeHistory.of(accountId, "dailyTransferLimit",
                    account.getDailyTransferLimit(), request.getDailyTransferLimit(), actor, request.getReason()));
            account.setDailyTransferLimit(request.getDailyTransferLimit());
        }

        if (!account.isOverdraftAllowed() && account.getOverdraftLimit().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleViolationException(
                    "An overdraft limit cannot be set while overdraft is not allowed");
        }
        if (changes.isEmpty()) {
            return AccountResponse.from(account);
        }

        historyRepository.saveAll(changes);
        Account saved = accountRepository.save(account);
        log.info("{} changed {} limit field(s) on account {}: {}", actor, changes.size(), accountId,
                changes.stream().map(AccountChangeHistory::getFieldName).toList());
        return AccountResponse.from(saved);
    }

    @Transactional
    public AccountResponse applyStatus(Long accountId, AccountStatusRequest request) {
        Account account = lock(accountId);

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessRuleViolationException("Account " + accountId + " is closed and cannot change status");
        }
        if (account.getStatus() == request.getStatus()) {
            return AccountResponse.from(account);
        }
        if (request.getStatus() == AccountStatus.CLOSED) {
            assertZeroBalance(account);
            account.setClosedAt(LocalDateTime.now());
        }

        String actor = SecurityUtils.currentUsername();
        historyRepository.save(AccountChangeHistory.of(accountId, "status",
                account.getStatus(), request.getStatus(), actor, request.getReason()));
        account.setStatus(request.getStatus());

        Account saved = accountRepository.save(account);
        log.info("{} set account {} to {} ({})", actor, accountId, request.getStatus(), request.getReason());
        return AccountResponse.from(saved);
    }

    @Transactional
    public void close(Long accountId, String reason) {
        Account account = lock(accountId);
        if (account.getStatus() == AccountStatus.CLOSED) {
            return;
        }
        assertZeroBalance(account);

        String actor = SecurityUtils.currentUsername();
        historyRepository.save(AccountChangeHistory.of(accountId, "status",
                account.getStatus(), AccountStatus.CLOSED, actor, reason));

        account.setStatus(AccountStatus.CLOSED);
        account.setClosedAt(LocalDateTime.now());
        accountRepository.save(account);
        log.info("{} closed account {} ({})", actor, accountId, reason);
    }

    /**
     * Row-locked read.
     *
     * <p>The lock also serialises against an in-flight transfer on the same account, so an account
     * cannot be frozen halfway through a debit-credit pair.</p>
     */
    private Account lock(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private static void assertNotClosed(Account account) {
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessRuleViolationException("Account " + account.getId() + " is closed");
        }
    }

    /** Money must be swept out before an account is closed, or the ledger stops balancing. */
    private static void assertZeroBalance(Account account) {
        if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessRuleViolationException("NON_ZERO_BALANCE_ON_CLOSE",
                    "Account " + account.getId() + " still holds " + account.getBalance() + " "
                            + account.getCurrency() + ". Transfer the balance out before closing it.");
        }
    }
}
