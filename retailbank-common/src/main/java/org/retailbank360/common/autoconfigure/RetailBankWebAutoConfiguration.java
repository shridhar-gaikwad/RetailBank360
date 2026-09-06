package org.retailbank360.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.retailbank360.common.config.RateLimitProperties;
import org.retailbank360.common.ratelimit.RateLimitFilter;
import org.retailbank360.common.ratelimit.SlidingWindowRateLimiter;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.retailbank360.common.web.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Servlet-only web infrastructure: correlation ids, the shared error contract and rate limiting.
 *
 * <p>Guarded by {@code @ConditionalOnWebApplication(SERVLET)} so the reactive api-gateway, which has
 * no servlet API on its classpath, is untouched.</p>
 */
@AutoConfiguration
@ConditionalOnClass(Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RetailBankWebAutoConfiguration {

    /** Assigns and propagates {@code X-Correlation-Id} for cross-service tracing. */
    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    /** One error payload shape for every service. */
    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public SlidingWindowRateLimiter slidingWindowRateLimiter() {
        return new SlidingWindowRateLimiter();
    }

    /**
     * Enforces {@code retailbank.rate-limit.rules}. Each service configures its own rules, which is
     * how login brute-force protection and the transfer budget are applied without hard-coding paths
     * into the shared module.
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimitFilter rateLimitFilter(RateLimitProperties properties,
                                           SlidingWindowRateLimiter limiter,
                                           ObjectMapper objectMapper) {
        return new RateLimitFilter(properties, limiter, objectMapper);
    }
}
