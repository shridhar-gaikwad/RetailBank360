package org.retailbank360.common.notification;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.NotificationProperties;
import org.retailbank360.common.dto.NotificationEventRequest;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tells notification-service that something happened a customer should hear about.
 *
 * <p>Built on the same three rules as {@code AuditPublisher}, for the same reasons:</p>
 * <ul>
 *   <li><b>Never blocking.</b> Dispatch happens on a small bounded pool, so a slow or dead
 *       notification service cannot add latency to a transfer.</li>
 *   <li><b>Never failing the caller.</b> A transport error is logged and the event is dropped. A
 *       customer not receiving an SMS is an inconvenience; a transfer failing because of it is a
 *       defect.</li>
 *   <li><b>Never anonymous.</b> Each call carries a short lived service JWT.</li>
 * </ul>
 *
 * <p>The event carries only a customer id, so a publishing service never touches contact details.</p>
 */
@Slf4j
public class NotificationPublisher {

    private final NotificationProperties properties;
    private final RestClient restClient;
    private final JwtTokenService tokenService;
    private final String serviceName;
    private final ThreadPoolExecutor executor;

    public NotificationPublisher(NotificationProperties properties, RestClient restClient,
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
                    Thread thread = new Thread(runnable, "notification-publisher");
                    thread.setDaemon(true);
                    return thread;
                },
                // Under a flood, drop the oldest queued notification rather than block a banking thread.
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** Fire-and-forget. Fills in the source service and correlation id automatically. */
    public void publish(NotificationEventRequest event) {
        if (event.getCustomerId() == null) {
            log.debug("Skipping {} notification with no customer id", event.getEventType());
            return;
        }
        if (event.getSourceService() == null) {
            event.setSourceService(serviceName);
        }
        if (event.getCorrelationId() == null) {
            event.setCorrelationId(CorrelationIdFilter.currentCorrelationId());
        }

        if (!properties.isEnabled()) {
            log.info("NOTIFY {} to customer {}: {}", event.getEventType(), event.getCustomerId(),
                    event.getSubject());
            return;
        }
        try {
            executor.execute(() -> send(event));
        } catch (RuntimeException e) {
            log.warn("Notification {} for customer {} could not be queued",
                    event.getEventType(), event.getCustomerId());
        }
    }

    /** Convenience for the common case. */
    public void notifyCustomer(Long customerId, String eventType, String referenceId,
                               String subject, String message) {
        publish(NotificationEventRequest.builder()
                .customerId(customerId)
                .eventType(eventType)
                .referenceId(referenceId)
                .subject(subject)
                .message(message)
                .build());
    }

    private void send(NotificationEventRequest event) {
        try {
            restClient.post()
                    .uri(properties.getUrl() + "/api/v1/notifications/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + tokenService.generateServiceToken(serviceName))
                    .header(CorrelationIdFilter.HEADER,
                            event.getCorrelationId() == null ? "" : event.getCorrelationId())
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Notification not delivered ({}): {} for customer {} ref {}",
                    e.getMessage(), event.getEventType(), event.getCustomerId(), event.getReferenceId());
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
