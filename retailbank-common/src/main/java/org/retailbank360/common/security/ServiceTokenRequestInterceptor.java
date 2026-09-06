package org.retailbank360.common.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.springframework.http.HttpHeaders;

/**
 * Attaches credentials and tracing headers to every outgoing Feign call.
 *
 * <p>Internal endpoints such as the ledger API of account-service require a {@code SERVICE} role, so
 * service-to-service traffic is authenticated rather than merely firewalled. The token is minted per
 * request with a short TTL, so nothing long-lived has to be stored or rotated.</p>
 *
 * <p>The correlation id is forwarded as well, which is what lets one transfer or disbursement be
 * followed across three services and their audit trails.</p>
 */
@Slf4j
public class ServiceTokenRequestInterceptor implements RequestInterceptor {

    private final JwtTokenService tokenService;
    private final String serviceName;

    public ServiceTokenRequestInterceptor(JwtTokenService tokenService, String serviceName) {
        this.tokenService = tokenService;
        this.serviceName = serviceName;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (!template.headers().containsKey(HttpHeaders.AUTHORIZATION)) {
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.generateServiceToken(serviceName));
        }
        String correlationId = CorrelationIdFilter.currentCorrelationId();
        if (correlationId != null && !template.headers().containsKey(CorrelationIdFilter.HEADER)) {
            template.header(CorrelationIdFilter.HEADER, correlationId);
        }
    }
}
