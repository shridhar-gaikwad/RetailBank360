package org.retailbank360.client;

import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Client for the internal ledger API.
 *
 * <p>Disbursement credits the customer's account and repayment debits it. Both carry the loan
 * reference as their idempotency key, so a retry after a timeout cannot fund a loan twice.</p>
 *
 * <p>The {@code name} is the registered application name, and the {@code url} carries an empty
 * default. When a URL is configured Feign calls it directly; when the discovery profile blanks it,
 * Feign resolves the name through the registry and load-balances across the live instances.</p>
 */
@FeignClient(name = "retailbank-account-service", contextId = "loanAccountClient",
        url = "${retailbank.services.account.url:}")
public interface AccountServiceClient {

    @PostMapping("/api/v1/accounts/internal/credit")
    MoneyMovementResponse credit(@RequestBody MoneyMovementRequest request,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey);

    @PostMapping("/api/v1/accounts/internal/debit")
    MoneyMovementResponse debit(@RequestBody MoneyMovementRequest request,
                                @RequestHeader("Idempotency-Key") String idempotencyKey);

    /** Compensating action, used when a repayment was collected but could not be applied. */
    @PostMapping("/api/v1/accounts/internal/reverse")
    MoneyMovementResponse reverse(@RequestParam("reference") String reference,
                                  @RequestParam("reason") String reason,
                                  @RequestParam("accountIds") List<Long> accountIds);

    @GetMapping("/api/v1/accounts/internal/accounts/{accountId}/summary")
    Map<String, Object> accountSummary(@PathVariable("accountId") Long accountId);

    /** Asks the ledger what was actually posted, to settle an ambiguous disbursement. */
    @GetMapping("/api/v1/accounts/internal/entries/{reference}")
    List<Map<String, Object>> entriesForReference(@PathVariable("reference") String reference);
}
