package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.dto.AccountChangeHistoryResponse;
import org.retailbank360.dto.AccountLimitsRequest;
import org.retailbank360.dto.AccountRequest;
import org.retailbank360.dto.AccountResponse;
import org.retailbank360.dto.AccountStatusRequest;
import org.retailbank360.dto.LedgerEntryResponse;
import org.retailbank360.dto.StatementResponse;
import org.retailbank360.service.AccountService;
import org.retailbank360.service.AccountStatementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Customer-facing and staff account endpoints.
 *
 * <p>Money is never moved from here. Deposits, withdrawals and transfers are submitted to
 * transaction-service, which orchestrates them and calls the internal ledger API. Keeping the two
 * apart means there is exactly one code path that can change a balance.</p>
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountStatementService statementService;

    public AccountController(AccountService accountService, AccountStatementService statementService) {
        this.accountService = accountService;
        this.statementService = statementService;
    }

    /** Opens an account. Refused unless the customer has passed KYC. */
    @PostMapping
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody AccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/all")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<List<AccountResponse>> getAllAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(accountService.getAllAccounts(page, size));
    }

    /** Readable by staff, and by the customer who owns the account. */
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccountById(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountById(id));
    }

    @GetMapping("/number/{accountNumber}")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<AccountResponse> getAccountByNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.getAccountByNumber(accountNumber));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<AccountResponse>> getAccountsByCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(accountService.getAccountsByCustomer(customerId));
    }

    /** Adjusts minimum balance, overdraft and daily transfer ceiling. Recorded in change history. */
    @PutMapping("/{id}/limits")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<AccountResponse> updateLimits(@PathVariable Long id,
                                                        @Valid @RequestBody AccountLimitsRequest request) {
        return ResponseEntity.ok(accountService.updateLimits(id, request));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable Long id,
                                                        @Valid @RequestBody AccountStatusRequest request) {
        return ResponseEntity.ok(accountService.updateStatus(id, request));
    }

    /** Closes the account. Refused while it still holds a balance. */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id,
                                              @RequestParam(required = false) String reason) {
        accountService.closeAccount(id, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<AccountChangeHistoryResponse>> getChangeHistory(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getChangeHistory(id));
    }

    /** Statement for a date range; defaults to the last month. */
    @GetMapping("/{id}/statement")
    public ResponseEntity<StatementResponse> getStatement(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(statementService.generate(id, from, to));
    }

    /** Same statement as a downloadable CSV file. */
    @GetMapping(value = "/{id}/statement/export", produces = "text/csv")
    public ResponseEntity<String> exportStatement(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        StatementResponse statement = statementService.generate(id, from, to);
        String filename = "statement-" + id + "-" + statement.getFromDate() + "-to-" + statement.getToDate() + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(statementService.toCsv(statement));
    }

    /** Recent ledger lines, newest first. */
    @GetMapping("/{id}/ledger")
    public ResponseEntity<List<LedgerEntryResponse>> getLedger(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(statementService.recentEntries(id, page, size));
    }
}
