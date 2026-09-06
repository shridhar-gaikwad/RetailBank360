package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.retailbank360.entity.AuditEvent;

import java.time.Instant;

/** Audit record as returned by the admin views. */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEventResponse {

    private Long id;

    private String sourceService;

    private String action;

    private String entityType;

    private String entityId;

    private String actorUsername;

    private String actorRole;

    private String operationId;

    private String correlationId;

    private String outcome;

    private String details;

    private Instant occurredAt;

    private Instant recordedAt;

    /** Exposed so an auditor can spot-check the chain outside the verification endpoint. */
    private String eventHash;

    private String previousHash;

    public static AuditEventResponse from(AuditEvent event) {
        return AuditEventResponse.builder()
                .id(event.getId())
                .sourceService(event.getSourceService())
                .action(event.getAction())
                .entityType(event.getEntityType())
                .entityId(event.getEntityId())
                .actorUsername(event.getActorUsername())
                .actorRole(event.getActorRole())
                .operationId(event.getOperationId())
                .correlationId(event.getCorrelationId())
                .outcome(event.getOutcome())
                .details(event.getDetails())
                .occurredAt(event.getOccurredAt())
                .recordedAt(event.getRecordedAt())
                .eventHash(event.getEventHash())
                .previousHash(event.getPreviousHash())
                .build();
    }
}
