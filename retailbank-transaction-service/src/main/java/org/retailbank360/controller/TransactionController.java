package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.dto.DepositRequest;
import org.retailbank360.dto.TransactionResponse;
import org.retailbank360.dto.TransferRequest;
import org.retailbank360.dto.WithdrawRequest;
import org.retailbank360.service.TransactionService;
import org.springframework.http.HttpStatus;
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
 * Deposit, withdrawal and transfer endpoints.
 *
 * <p>Every write accepts an {@code Idempotency-Key} header (or the equivalent body field). Sending
 * the same key twice returns the original result instead of moving money again, which is what makes
 * a client-side retry after a timeout safe.</p>
 *
 * <p>Role rules: deposits are teller work; withdrawals and transfers may be initiated by the
 * customer who owns the source account, or by staff on their behalf. Ownership is checked per row
 * inside the service.</p>
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<TransactionResponse> deposit(
            @Valid @RequestBody DepositRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        applyHeaderKey(request::setIdempotencyKey, request.getIdempotencyKey(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.deposit(request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @Valid @RequestBody WithdrawRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        applyHeaderKey(request::setIdempotencyKey, request.getIdempotencyKey(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.withdraw(request));
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        applyHeaderKey(request::setIdempotencyKey, request.getIdempotencyKey(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.transfer(request));
    }

    @GetMapping("/{transactionRef}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable String transactionRef) {
        return ResponseEntity.ok(transactionService.getTransaction(transactionRef));
    }

    @GetMapping("/all")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<List<TransactionResponse>> getAllTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(transactionService.getAllTransactions(page, size));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByAccount(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(transactionService.getTransactionsByAccount(accountId, page, size));
    }

    /** Compensating action. Restricted to administrators, since it moves money back. */
    @PostMapping("/{transactionRef}/reverse")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<TransactionResponse> reverse(@PathVariable String transactionRef,
                                                       @RequestParam String reason) {
        return ResponseEntity.ok(transactionService.reverse(transactionRef, reason));
    }

    /** Forces an immediate reconciliation sweep instead of waiting for the scheduled one. */
    @PostMapping("/reconcile")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> reconcile() {
        return ResponseEntity.ok(Map.of("settled", transactionService.reconcileStalePending()));
    }

    /** The header wins only when the body did not carry a key, so both styles work. */
    private static void applyHeaderKey(java.util.function.Consumer<String> setter,
                                       String bodyKey, String headerKey) {
        if ((bodyKey == null || bodyKey.isBlank()) && headerKey != null && !headerKey.isBlank()) {
            setter.accept(headerKey);
        }
    }
}
