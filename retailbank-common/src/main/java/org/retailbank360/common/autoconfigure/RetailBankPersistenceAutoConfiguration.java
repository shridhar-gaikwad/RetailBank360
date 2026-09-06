package org.retailbank360.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.retailbank360.common.config.LockProperties;
import org.retailbank360.common.idempotency.IdempotencyRecordRepository;
import org.retailbank360.common.idempotency.IdempotencyService;
import org.retailbank360.common.lock.DatabaseDistributedLockManager;
import org.retailbank360.common.lock.DistributedLockManager;
import org.retailbank360.common.lock.LockAdminController;
import org.retailbank360.common.lock.LockAuditEventRepository;
import org.retailbank360.common.lock.LockAuditService;
import org.retailbank360.common.lock.LockReaper;
import org.retailbank360.common.lock.LockTemplate;
import org.retailbank360.common.lock.ResourceLockRepository;
import org.retailbank360.common.retry.ConcurrencyRetryTemplate;
import org.retailbank360.common.web.DataAccessExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Wires the database-backed locking framework and the idempotency store.
 *
 * <p>Only activates when the importing service actually has JPA and a live
 * {@link EntityManagerFactory}, so a service without a database is unaffected. Everything it
 * publishes is {@code @ConditionalOnMissingBean}, so a deployment can substitute a Redis or
 * ZooKeeper {@link DistributedLockManager} by simply declaring its own bean.</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"})
@ConditionalOnClass(EntityManagerFactory.class)
@ConditionalOnBean(EntityManagerFactory.class)
@EnableConfigurationProperties(LockProperties.class)
@EnableScheduling
public class RetailBankPersistenceAutoConfiguration {

    /**
     * Transaction template used for lock and idempotency bookkeeping.
     *
     * <p>{@code REQUIRES_NEW} is the crux of the design: taking a lock must be committed and visible
     * to other nodes immediately, and releasing it must survive a rollback of the business
     * transaction the lock was protecting.</p>
     */
    @Bean("retailBankRequiresNewTransactionTemplate")
    @ConditionalOnMissingBean(name = "retailBankRequiresNewTransactionTemplate")
    public TransactionTemplate retailBankRequiresNewTransactionTemplate(PlatformTransactionManager txManager) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    public ConcurrencyRetryTemplate concurrencyRetryTemplate(LockProperties properties) {
        return new ConcurrencyRetryTemplate(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockAuditService lockAuditService(
            LockAuditEventRepository repository,
            @Qualifier("retailBankRequiresNewTransactionTemplate") TransactionTemplate requiresNew) {
        return new LockAuditService(repository, requiresNew);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLockManager.class)
    @ConditionalOnProperty(prefix = "retailbank.lock", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public DistributedLockManager distributedLockManager(
            ResourceLockRepository lockRepository,
            LockAuditService auditService,
            LockProperties properties,
            @Qualifier("retailBankRequiresNewTransactionTemplate") TransactionTemplate requiresNew,
            @Value("${spring.application.name:retailbank-service}") String applicationName,
            @Value("${server.port:0}") String serverPort) {

        return new DatabaseDistributedLockManager(lockRepository, auditService, properties, requiresNew,
                resolveNodeId(properties, applicationName, serverPort));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DistributedLockManager.class)
    public LockTemplate lockTemplate(DistributedLockManager lockManager, ConcurrencyRetryTemplate retryTemplate) {
        return new LockTemplate(lockManager, retryTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DistributedLockManager.class)
    public LockReaper lockReaper(DistributedLockManager lockManager, LockAuditService auditService,
                                 LockProperties properties) {
        return new LockReaper(lockManager, auditService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyService idempotencyService(
            IdempotencyRecordRepository repository,
            ObjectMapper objectMapper,
            @Qualifier("retailBankRequiresNewTransactionTemplate") TransactionTemplate requiresNew) {
        return new IdempotencyService(repository, objectMapper, requiresNew);
    }

    /** Maps optimistic/pessimistic/deadlock failures onto the shared error contract. */
    @Bean
    @ConditionalOnClass(HttpServletRequest.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public DataAccessExceptionHandler dataAccessExceptionHandler() {
        return new DataAccessExceptionHandler();
    }

    /** Administrative and UI-facing view over the lock registry. */
    @Bean
    @ConditionalOnClass(HttpServletRequest.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBean(DistributedLockManager.class)
    @ConditionalOnMissingBean
    public LockAdminController lockAdminController(DistributedLockManager lockManager,
                                                   LockAuditService auditService) {
        return new LockAdminController(lockManager, auditService);
    }

    /**
     * Registers the housekeeping tasks.
     *
     * <p>Done programmatically rather than with {@code @Scheduled} so the reaper interval can be a
     * bound {@link Duration} property instead of a string the annotation would have to parse.</p>
     */
    @Bean
    @ConditionalOnBean(LockReaper.class)
    public SchedulingConfigurer retailBankLockSchedulingConfigurer(LockReaper reaper,
                                                                   IdempotencyService idempotencyService,
                                                                   LockProperties properties) {
        return registrar -> {
            registrar.addFixedDelayTask(reaper::reap, properties.getReaperInterval());
            registrar.addFixedDelayTask(reaper::purgeAudit, Duration.ofHours(1));
            registrar.addFixedDelayTask(idempotencyService::purgeExpired, Duration.ofHours(1));
        };
    }

    /**
     * Builds the identity this JVM uses in the lock table.
     *
     * <p>Host, port and PID together keep two replicas of the same service distinguishable, which is
     * what makes the audit trail useful when diagnosing contention in a multi-instance deployment.</p>
     */
    private static String resolveNodeId(LockProperties properties, String applicationName, String serverPort) {
        if (properties.getNodeId() != null && !properties.getNodeId().isBlank()) {
            return properties.getNodeId();
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return applicationName + "@" + host + ":" + serverPort + "#" + ProcessHandle.current().pid();
    }
}
