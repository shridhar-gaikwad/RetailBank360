package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.dto.LedgerEntryResponse;
import org.retailbank360.service.AccountService;
import org.retailbank360.service.AccountStatementService;
import org.retailbank360.service.MoneyMovementService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal ledger API. The single entry point through which any balance in the bank can change.
 *
 * <p>Callers are other microservices - transaction-service for deposits, withdrawals and transfers,
 * loan-service for disbursements and repayments - authenticated with a {@code SERVICE} token minted
 * by the shared Feign interceptor. Administrators may also reach it directly, which is what makes
 * the manual reversal usable during an incident.</p>
 *
 * <p>Every operation takes an {@code Idempotency-Key} header. A replay returns the original result
 * rather than moving money twice, which is also what makes the internal deadlock retries safe.</p>
 */
@RestController
@RequestMapping("/api/v1/accounts/internal")
@PreAuthorize(Roles.HAS_STAFF_OR_SERVICE)
public class InternalLedgerController {

    private final MoneyMovementService moneyMovementService;
    private final AccountStatementService statementService;
    private final AccountService accountService;

    public InternalLedgerController(MoneyMovementService moneyMovementService,
                                    AccountStatementService statementService,
                                    AccountService accountService) {
        this.moneyMovementService = moneyMovementService;
        this.statementService = statementService;
        this.accountService = accountService;
    }

    /** Debit one account and credit another inside a single database transaction. */
    @PostMapping("/transfer")
    public ResponseEntity<MoneyMovementResponse> transfer(
            @Valid @RequestBody TransferInstruction instruction,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(moneyMovementService.transfer(instruction, idempotencyKey));
    }

    @PostMapping("/credit")
    public ResponseEntity<MoneyMovementResponse> credit(
            @Valid @RequestBody MoneyMovementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(moneyMovementService.credit(request, idempotencyKey));
    }

    @PostMapping("/debit")
    public ResponseEntity<MoneyMovementResponse> debit(
            @Valid @RequestBody MoneyMovementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(moneyMovementService.debit(request, idempotencyKey));
    }

    /**
     * Compensating action of a saga: writes mirror-image entries for everything posted under
     * {@code reference}. Nothing is deleted, so the ledger records both the movement and its undo.
     */
    @PostMapping("/reverse")
    public ResponseEntity<MoneyMovementResponse> reverse(@RequestParam String reference,
                                                         @RequestParam String reason,
                                                         @RequestParam List<Long> accountIds) {
        return ResponseEntity.ok(moneyMovementService.reverse(reference, reason, accountIds));
    }

    /**
     * Ownership and currency of an account.
     *
     * <p>Orchestrating services call this once, when they record a transaction, so they can later
     * answer "is this row yours?" without a round trip on every read. Deliberately minimal: it
     * exposes no balance and no personal data.</p>
     */
    @GetMapping("/accounts/{accountId}/summary")
    public ResponseEntity<Map<String, Object>> accountSummary(@PathVariable Long accountId) {
        return ResponseEntity.ok(accountService.getAccountSummary(accountId));
    }

    /** Both legs of one business reference, used by orchestrators to confirm what was posted. */
    @GetMapping("/entries/{reference}")
    public ResponseEntity<List<LedgerEntryResponse>> entriesForReference(@PathVariable String reference) {
        return ResponseEntity.ok(statementService.entriesForReference(reference));
    }
}
