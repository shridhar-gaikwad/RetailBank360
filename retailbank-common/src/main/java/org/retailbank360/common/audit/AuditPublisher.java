package org.retailbank360.common.audit;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.AuditProperties;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ships business audit events to the audit-service.
 *
 * <p>Three properties matter here:</p>
 * <ul>
 *   <li><b>Never blocking.</b> Publishing happens on a small bounded pool, so a slow or dead audit
 *       service cannot add latency to a transfer.</li>
 *   <li><b>Never failing the caller.</b> Any transport error is logged, and the event is written to
 *       the local application log as a fallback, so the trail is not lost outright.</li>
 *   <li><b>Never anonymous.</b> Each call carries a short lived service JWT, so the audit endpoint
 *       can require a {@code SERVICE} role instead of being open.</li>
 * </ul>
 *
 * <p>Uses {@code RestClient} from spring-web rather than Feign or Kafka, so the same publisher works
 * in every service without adding a dependency or requiring a broker to be running.</p>
 */
@Slf4j
public class AuditPublisher {

    private final AuditProperties properties;
    private final RestClient restClient;
    private final JwtTokenService tokenService;
    private final String serviceName;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    public AuditPublisher(AuditProperties properties, RestClient restClient,
                          JwtTokenService tokenService, String serviceName) {
        this.properties = properties;
        this.restClient = restClient;
        this.tokenService = tokenService;
        this.serviceName = serviceName;
        this.executor = new ThreadPoolExecutor(
                1, Math.max(1, properties.getWorkerThreads()),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "audit-publisher");
                    thread.setDaemon(true);
                    return thread;
                },
                // Under a flood, drop the oldest queued event rather than block a banking thread.
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** Fire-and-forget publish. Fills in source service, actor and correlation id automatically. */
    public void publish(AuditEventRequest event) {
        enrich(event);
        if (!properties.isEnabled()) {
            log.info("AUDIT {} {} {} by {} -> {}", event.getAction(), event.getEntityType(),
                    event.getEntityId(), event.getActorUsername(), event.getOutcome());
            return;
        }
        try {
            executor.execute(() -> send(event));
        } catch (RuntimeException e) {
            log.warn("Audit event {} could not be queued ({} dropped so far)",
                    event.getAction(), dropped.incrementAndGet());
        }
    }

    /** Convenience for the common "something succeeded" case. */
    public void publishSuccess(String action, String entityType, Object entityId, String operationId) {
        publish(AuditEventRequest.builder()
                .action(action)
                .entityType(entityType)
                .entityId(entityId == null ? null : String.valueOf(entityId))
                .operationId(operationId)
                .outcome(AuditActions.OUTCOME_SUCCESS)
                .build());
    }

    /** Convenience for the common "something was rejected" case. */
    public void publishFailure(String action, String entityType, Object entityId, String operationId,
                               String reason) {
        publish(AuditEventRequest.builder()
                .action(action)
                .entityType(entityType)
                .entityId(entityId == null ? null : String.valueOf(entityId))
                .operationId(operationId)
                .outcome(AuditActions.OUTCOME_FAILURE)
                .build()
                .with("reason", reason));
    }

    private void enrich(AuditEventRequest event) {
        if (event.getSourceService() == null) {
            event.setSourceService(serviceName);
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(Instant.now());
        }
        if (event.getCorrelationId() == null) {
            event.setCorrelationId(CorrelationIdFilter.currentCorrelationId());
        }
        if (event.getActorUsername() == null) {
            event.setActorUsername(SecurityUtils.currentUsername());
        }
        if (event.getActorRole() == null) {
            SecurityUtils.currentUser().ifPresent(user -> event.setActorRole(user.role()));
        }
        if (event.getOutcome() == null) {
            event.setOutcome(AuditActions.OUTCOME_SUCCESS);
        }
    }

    private void send(AuditEventRequest event) {
        try {
            restClient.post()
                    .uri(properties.getUrl() + "/api/v1/audit/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + tokenService.generateServiceToken(serviceName))
                    .header(CorrelationIdFilter.HEADER,
                            event.getCorrelationId() == null ? "" : event.getCorrelationId())
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // The audit service being down must never break banking. Keep the event in the local log
            // so it can still be reconciled later.
            log.warn("Audit event not delivered ({}). Falling back to local log: {} {} {} by {} -> {}",
                    e.getMessage(), event.getAction(), event.getEntityType(), event.getEntityId(),
                    event.getActorUsername(), event.getOutcome());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
