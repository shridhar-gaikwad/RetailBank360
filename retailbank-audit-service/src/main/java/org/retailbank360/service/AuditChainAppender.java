package org.retailbank360.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.entity.AuditEvent;
import org.retailbank360.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Transactional append to the audit hash chain.
 *
 * <p>Separate from {@link AuditEventService} so the lock can be taken outside the transaction: read
 * the tail, compute the next hash and insert must all happen in one transaction, and that
 * transaction must be entered through a proxy rather than by self-invocation.</p>
 */
@Slf4j
@Service
public class AuditChainAppender {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditChainAppender(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AuditEvent append(AuditEventRequest request) {
        AuditEvent event = new AuditEvent();
        event.setSourceService(defaulted(request.getSourceService(), "unknown-service"));
        event.setAction(defaulted(request.getAction(), "UNSPECIFIED"));
        event.setEntityType(request.getEntityType());
        event.setEntityId(request.getEntityId());
        event.setActorUsername(request.getActorUsername());
        event.setActorRole(request.getActorRole());
        event.setOperationId(request.getOperationId());
        event.setCorrelationId(request.getCorrelationId());
        event.setOutcome(defaulted(request.getOutcome(), "SUCCESS"));
        event.setDetails(serialiseDetails(request));
        // Truncated to the precision the database actually keeps, so the value that is hashed is
        // exactly the value that is stored. Together with the epoch-millis encoding in
        // canonicalForm(), this is what makes the chain verifiable after a round trip.
        Instant occurredAt = request.getOccurredAt() == null ? Instant.now() : request.getOccurredAt();
        event.setOccurredAt(occurredAt.truncatedTo(ChronoUnit.MILLIS));
        event.setRecordedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        // Read the tail of this service's shard inside the same transaction that inserts, under the
        // caller's lock on that shard.
        String previousHash = repository
                .findFirstBySourceServiceOrderByIdDesc(event.getSourceService())
                .map(AuditEvent::getEventHash)
                .orElse(null);
        event.setPreviousHash(previousHash);
        event.setEventHash(sha256(event.canonicalForm()));

        AuditEvent saved = repository.save(event);
        log.debug("Recorded audit event {} {} from {}", saved.getAction(), saved.getEntityId(),
                saved.getSourceService());
        return saved;
    }

    private String serialiseDetails(AuditEventRequest request) {
        if (request.getDetails() == null || request.getDetails().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.getDetails());
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise audit details for {}", request.getAction(), e);
            return String.valueOf(request.getDetails());
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
