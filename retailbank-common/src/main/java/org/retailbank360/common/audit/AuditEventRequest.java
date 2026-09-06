package org.retailbank360.common.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Business audit event shipped to the audit-service.
 *
 * <p>Deliberately flat and self-describing: the audit-service stores it without needing to know
 * anything about the domain of the service that produced it.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEventRequest {

    /** Service that produced the event, e.g. {@code retailbank-account-service}. */
    private String sourceService;

    /** What happened, e.g. {@code TRANSFER_COMPLETED}. See {@link AuditActions}. */
    private String action;

    /** Domain object touched, e.g. {@code ACCOUNT}. */
    private String entityType;

    private String entityId;

    private String actorUsername;

    private String actorRole;

    /** Business operation id, so every event of one saga can be pulled together. */
    private String operationId;

    private String correlationId;

    /** {@code SUCCESS} or {@code FAILURE}. */
    private String outcome;

    /** Before/after values and any other context worth keeping. */
    private Map<String, Object> details;

    private Instant occurredAt;

    /** Fluent helper so call sites read as one statement. */
    public AuditEventRequest with(String key, Object value) {
        if (details == null) {
            details = new LinkedHashMap<>();
        }
        details.put(key, value);
        return this;
    }
}
