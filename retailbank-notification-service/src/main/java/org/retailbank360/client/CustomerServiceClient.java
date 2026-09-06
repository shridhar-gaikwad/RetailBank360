package org.retailbank360.client;

import org.retailbank360.client.dto.CustomerContact;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Resolves who to deliver a notification to.
 *
 * <p>Doing the lookup here rather than in the publishing service is the whole point: transaction-
 * service and loan-service emit a customer id and never touch an email address or a phone number.</p>
 *
 * <p>The {@code name} is the registered application name, and the {@code url} carries an empty
 * default. When a URL is configured Feign calls it directly; when the discovery profile blanks it,
 * Feign resolves the name through the registry and load-balances across the live instances.</p>
 */
@FeignClient(name = "retailbank-customer-service", contextId = "notificationCustomerClient",
        url = "${retailbank.services.customer.url:}")
public interface CustomerServiceClient {

    @GetMapping("/api/v1/customers/internal/{id}/contact")
    CustomerContact getContact(@PathVariable("id") Long customerId);
}
