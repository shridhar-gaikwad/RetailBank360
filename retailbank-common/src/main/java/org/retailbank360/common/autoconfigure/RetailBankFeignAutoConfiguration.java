package org.retailbank360.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.retailbank360.common.client.RetailBankFeignErrorDecoder;
import org.retailbank360.common.config.SecurityProperties;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.common.security.ServiceTokenRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Defaults applied to the Feign clients of any service that makes internal calls.
 *
 * <h2>Why this is a class of its own</h2>
 * The condition has to sit on the <em>class</em>, not on the individual beans. Spring introspects a
 * configuration class reflectively, which loads the return type of every {@code @Bean} method - so a
 * method returning a Feign type in a shared configuration would throw {@code NoClassDefFoundError}
 * in a service that has no Feign on its classpath, long before any method-level condition is
 * consulted. A class-level {@code @ConditionalOnClass} is evaluated from bytecode metadata instead,
 * so the class is never loaded at all when Feign is absent.
 *
 * <p>auth-service and notification-service make no outbound service calls, and this is exactly what
 * keeps the shared module usable by them.</p>
 */
@AutoConfiguration(after = RetailBankCoreAutoConfiguration.class)
@ConditionalOnClass({RequestInterceptor.class, ErrorDecoder.class})
@EnableConfigurationProperties(SecurityProperties.class)
public class RetailBankFeignAutoConfiguration {

    /**
     * Signs every outgoing call with a short-lived service token and forwards the correlation id, so
     * internal endpoints can require a {@code SERVICE} role instead of being open.
     */
    @Bean
    @ConditionalOnMissingBean(ServiceTokenRequestInterceptor.class)
    public ServiceTokenRequestInterceptor serviceTokenRequestInterceptor(
            JwtTokenService jwtTokenService,
            @Value("${spring.application.name:retailbank-service}") String serviceName) {
        return new ServiceTokenRequestInterceptor(jwtTokenService, serviceName);
    }

    /**
     * Rebuilds a downstream error as the exception it was raised as, so a business rejection in one
     * service does not surface as a 500 in the service that called it.
     */
    @Bean
    @ConditionalOnMissingBean(ErrorDecoder.class)
    public RetailBankFeignErrorDecoder retailBankFeignErrorDecoder(ObjectMapper objectMapper) {
        return new RetailBankFeignErrorDecoder(objectMapper);
    }
}
