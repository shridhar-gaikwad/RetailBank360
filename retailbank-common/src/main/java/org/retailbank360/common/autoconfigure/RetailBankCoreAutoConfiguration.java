package org.retailbank360.common.autoconfigure;

import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.config.AuditProperties;
import org.retailbank360.common.config.CryptoProperties;
import org.retailbank360.common.config.NotificationProperties;
import org.retailbank360.common.config.SecurityProperties;
import org.retailbank360.common.crypto.CryptoHolder;
import org.retailbank360.common.crypto.CryptoService;
import org.retailbank360.common.notification.NotificationPublisher;
import org.retailbank360.common.security.JwtTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Infrastructure that every RetailBank360 service needs regardless of how it is wired: JWT handling,
 * PII encryption and audit publishing.
 *
 * <p>Delivered as a Spring Boot auto-configuration rather than as component-scanned beans. That is
 * deliberate: the modules in this project all use the {@code org.retailbank360} package, so a plain
 * {@code @Component} in the shared jar would be picked up even by services that cannot support it
 * (the reactive gateway, the database-less notification service). Auto-configuration lets each bean
 * state its own preconditions and back off cleanly.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({SecurityProperties.class, CryptoProperties.class, AuditProperties.class,
        NotificationProperties.class})
public class RetailBankCoreAutoConfiguration {

    /** Mints and verifies the JWTs shared by the whole platform. Needs only jjwt, so always present. */
    @Bean
    @ConditionalOnMissingBean
    public JwtTokenService jwtTokenService(SecurityProperties properties) {
        return new JwtTokenService(properties);
    }

    /** AES-256-GCM for PII columns plus keyed blind indexes for exact-match lookups. */
    @Bean
    @ConditionalOnMissingBean
    public CryptoService cryptoService(CryptoProperties properties) {
        CryptoService service = new CryptoService(properties);
        // JPA attribute converters are built by the persistence provider, not by Spring, so they
        // reach the service through this static bridge.
        CryptoHolder.set(service);
        return service;
    }

    /** Short-timeout HTTP client used for audit publishing; never shares a pool with business calls. */
    @Bean("auditRestClient")
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean(name = "auditRestClient")
    public RestClient auditRestClient(AuditProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Short-timeout client for notification dispatch; separate pool from business calls. */
    @Bean("notificationRestClient")
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean(name = "notificationRestClient")
    public RestClient notificationRestClient(NotificationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean
    public NotificationPublisher notificationPublisher(
            NotificationProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("notificationRestClient")
            RestClient notificationRestClient,
            JwtTokenService jwtTokenService,
            @Value("${spring.application.name:retailbank-service}") String serviceName) {
        return new NotificationPublisher(properties, notificationRestClient, jwtTokenService, serviceName);
    }

    @Bean
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean
    public AuditPublisher auditPublisher(AuditProperties properties,
                                         @org.springframework.beans.factory.annotation.Qualifier("auditRestClient")
                                         RestClient auditRestClient,
                                         JwtTokenService jwtTokenService,
                                         @Value("${spring.application.name:retailbank-service}") String serviceName) {
        return new AuditPublisher(properties, auditRestClient, jwtTokenService, serviceName);
    }
}
