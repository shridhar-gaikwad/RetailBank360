package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.constants.LedgerDirection;
import org.retailbank360.dto.LedgerEntryResponse;
import org.retailbank360.dto.StatementResponse;
import org.retailbank360.entity.Account;
import org.retailbank360.entity.LedgerEntry;
import org.retailbank360.repository.AccountRepository;
import org.retailbank360.repository.LedgerEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builds account statements from the immutable ledger, and renders them as CSV for export.
 *
 * <p>The opening balance is read from the closing balance of the last entry before the window rather
 * than recomputed from a sum, so a statement can never drift away from the ledger it describes.</p>
 */
@Slf4j
@Service
public class AccountStatementService {

    /** Longest statement window accepted, to keep one request from scanning years of history. */
    private static final int MAX_STATEMENT_DAYS = 366;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;

    public AccountStatementService(AccountRepository accountRepository, LedgerEntryRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(readOnly = true)
    public StatementResponse generate(Long accountId, LocalDate from, LocalDate to) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        SecurityUtils.requireCustomerAccess(account.getCustomerId());

        LocalDate fromDate = from != null ? from : LocalDate.now().minusMonths(1);
        LocalDate toDate = to != null ? to : LocalDate.now();
        validateRange(fromDate, toDate);

        LocalDateTime start = fromDate.atStartOfDay();
        // Exclusive upper bound at midnight after toDate, so the last day is fully included.
        LocalDateTime end = toDate.plusDays(1).atStartOfDay();

        List<LedgerEntry> entries = ledgerRepository.findStatementEntries(accountId, start, end);

        // Every figure on a statement is money, so it is normalised to two decimals. An aggregate that
        // came back from SQL as a bare 0 would otherwise print as "0" next to amounts like "1250.00".
        BigDecimal opening = MoneyUtil.nullSafe(
                ledgerRepository.findOpeningBalance(accountId, start).orElse(BigDecimal.ZERO));
        BigDecimal closing = entries.isEmpty()
                ? opening
                : MoneyUtil.normalize(entries.get(entries.size() - 1).getBalanceAfter());

        return StatementResponse.builder()
                .accountId(accountId)
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(account.getAccountNumber()))
                .accountHolderRef("CUSTOMER-" + account.getCustomerId())
                .currency(account.getCurrency())
                .fromDate(fromDate)
                .toDate(toDate)
                .openingBalance(opening)
                .closingBalance(closing)
                .totalCredits(MoneyUtil.nullSafe(
                        ledgerRepository.sumByDirection(accountId, LedgerDirection.CREDIT, start, end)))
                .totalDebits(MoneyUtil.nullSafe(
                        ledgerRepository.sumByDirection(accountId, LedgerDirection.DEBIT, start, end)))
                .entryCount(entries.size())
                .generatedAt(LocalDateTime.now())
                .generatedBy(SecurityUtils.currentUsername())
                .entries(entries.stream().map(LedgerEntryResponse::from).toList())
                .build();
    }

    /** Most recent ledger entries for an account, newest first. */
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> recentEntries(Long accountId, int page, int size) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        SecurityUtils.requireCustomerAccess(account.getCustomerId());

        return ledgerRepository.findByAccountIdOrderByPostedAtDesc(accountId, PageRequest.of(page, size))
                .map(LedgerEntryResponse::from)
                .getContent();
    }

    /** Every entry produced by one business reference, i.e. both legs of a transfer. */
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> entriesForReference(String reference) {
        List<LedgerEntry> entries = ledgerRepository.findByReferenceOrderByPostedAtAsc(reference);
        if (entries.isEmpty()) {
            throw new ResourceNotFoundException("No ledger entries for reference " + reference);
        }
        return entries.stream().map(LedgerEntryResponse::from).toList();
    }

    /** CSV rendering of a statement, for the exportable-statement requirement. */
    public String toCsv(StatementResponse statement) {
        StringBuilder csv = new StringBuilder();
        csv.append("Account,").append(statement.getMaskedAccountNumber()).append(System.lineSeparator());
        csv.append("Period,").append(statement.getFromDate()).append(" to ").append(statement.getToDate())
                .append(System.lineSeparator());
        csv.append("Currency,").append(statement.getCurrency()).append(System.lineSeparator());
        csv.append("Opening Balance,").append(statement.getOpeningBalance()).append(System.lineSeparator());
        csv.append("Closing Balance,").append(statement.getClosingBalance()).append(System.lineSeparator());
        csv.append(System.lineSeparator());
        csv.append("Posted At,Entry Ref,Type,Direction,Amount,Balance After,Reference,Description")
                .append(System.lineSeparator());

        for (LedgerEntryResponse entry : statement.getEntries()) {
            csv.append(entry.getPostedAt()).append(',')
                    .append(entry.getEntryRef()).append(',')
                    .append(entry.getMovementType()).append(',')
                    .append(entry.getDirection()).append(',')
                    .append(entry.getAmount()).append(',')
                    .append(entry.getBalanceAfter()).append(',')
                    .append(escape(entry.getReference())).append(',')
                    .append(escape(entry.getDescription()))
                    .append(System.lineSeparator());
        }
        return csv.toString();
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessRuleViolationException("The statement start date must not be after the end date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_STATEMENT_DAYS) {
            throw new BusinessRuleViolationException(
                    "A statement may not span more than " + MAX_STATEMENT_DAYS + " days");
        }
    }

    /** Quotes a CSV field and neutralises the leading characters spreadsheets treat as formulas. */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        if ("=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
