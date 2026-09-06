package org.retailbank360.client;

import org.retailbank360.client.dto.CustomerProfile;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Reads the customer profile that account opening is gated on.
 *
 * <p>The bearer token is added by the shared {@code ServiceTokenRequestInterceptor}.</p>
 *
 * <p>The {@code name} is the registered application name, and the {@code url} carries an empty
 * default. When a URL is configured Feign calls it directly; when the discovery profile blanks it,
 * Feign resolves the name through the registry and load-balances across the live instances.</p>
 */
@FeignClient(name = "retailbank-customer-service", contextId = "customerClient",
        url = "${retailbank.services.customer.url:}")
public interface CustomerServiceClient {

    @GetMapping("/api/v1/customers/internal/{id}/profile")
    CustomerProfile getProfile(@PathVariable("id") Long customerId);
}
