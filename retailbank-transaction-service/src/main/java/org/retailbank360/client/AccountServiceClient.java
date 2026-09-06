package org.retailbank360.client;

import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
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
 * Client for the internal ledger API of account-service - the atomic step of every saga this
 * service orchestrates.
 *
 * <p>The bearer token and the correlation id are added by the shared
 * {@code ServiceTokenRequestInterceptor}; downstream errors are converted back into the original
 * exception types by {@code RetailBankFeignErrorDecoder}, so an "insufficient funds" here is still a
 * 422 to the customer rather than a 500.</p>
 *
 * <p>The {@code name} is the registered application name, and the {@code url} carries an empty
 * default. When a URL is configured Feign calls it directly; when the discovery profile blanks it,
 * Feign resolves the name through the registry and load-balances across the live instances.</p>
 */
@FeignClient(name = "retailbank-account-service", contextId = "accountClient",
        url = "${retailbank.services.account.url:}")
public interface AccountServiceClient {

    @PostMapping("/api/v1/accounts/internal/transfer")
    MoneyMovementResponse transfer(@RequestBody TransferInstruction instruction,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey);

    @PostMapping("/api/v1/accounts/internal/credit")
    MoneyMovementResponse credit(@RequestBody MoneyMovementRequest request,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey);

    @PostMapping("/api/v1/accounts/internal/debit")
    MoneyMovementResponse debit(@RequestBody MoneyMovementRequest request,
                                @RequestHeader("Idempotency-Key") String idempotencyKey);

    /** Compensating action: undoes everything posted under a reference. */
    @PostMapping("/api/v1/accounts/internal/reverse")
    MoneyMovementResponse reverse(@RequestParam("reference") String reference,
                                  @RequestParam("reason") String reason,
                                  @RequestParam("accountIds") List<Long> accountIds);

    /** Ownership, currency and status of an account, used for row-level authorization. */
    @GetMapping("/api/v1/accounts/internal/accounts/{accountId}/summary")
    Map<String, Object> accountSummary(@PathVariable("accountId") Long accountId);

    /**
     * Asks what was actually posted under a reference.
     *
     * <p>This is how the saga settles an ambiguous outcome: if the ledger call timed out, the money
     * may or may not have moved, and only the ledger itself can say which.</p>
     */
    @GetMapping("/api/v1/accounts/internal/entries/{reference}")
    List<Map<String, Object>> entriesForReference(@PathVariable("reference") String reference);
}
