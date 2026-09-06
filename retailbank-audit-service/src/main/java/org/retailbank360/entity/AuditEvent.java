package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One immutable audit record.
 *
 * <h2>Tamper evidence</h2>
 * Each row stores the hash of the row before it and a hash of its own content. Altering any
 * historical row breaks the chain from that point on, and {@code GET /api/v1/audit/verify} finds
 * exactly where. Deleting a row breaks it too. That is a much stronger guarantee than "the table is
 * append-only by convention", and it costs one SHA-256 per event.
 *
 * <p>Updates and deletes are rejected outright by the lifecycle callbacks below.</p>
 */
@Entity
@Table(name = "audit_events",
        indexes = {
                @Index(name = "ix_audit_action", columnList = "action"),
                @Index(name = "ix_audit_entity", columnList = "entity_type, entity_id"),
                @Index(name = "ix_audit_actor", columnList = "actor_username"),
                @Index(name = "ix_audit_occurred", columnList = "occurred_at"),
                @Index(name = "ix_audit_operation", columnList = "operation_id"),
                @Index(name = "ix_audit_correlation", columnList = "correlation_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Service that produced the event. */
    @Column(name = "source_service", nullable = false, length = 60)
    private String sourceService;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 40)
    private String entityType;

    @Column(name = "entity_id", length = 120)
    private String entityId;

    @Column(name = "actor_username", length = 120)
    private String actorUsername;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    /** Business operation id, so every event of one saga can be pulled together. */
    @Column(name = "operation_id", length = 120)
    private String operationId;

    @Column(name = "correlation_id", length = 120)
    private String correlationId;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    /** Context, stored as JSON. */
    @Lob
    @Column(name = "details")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** Hash of the previous event in the chain; null only for the very first row. */
    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    /** SHA-256 over this row's content and {@link #previousHash}. */
    @Column(name = "event_hash", nullable = false, length = 64)
    private String eventHash;

    @PreUpdate
    private void rejectUpdate() {
        throw new UnsupportedOperationException(
                "Audit events are immutable; event " + id + " cannot be modified");
    }

    @PreRemove
    private void rejectDelete() {
        throw new UnsupportedOperationException(
                "Audit events are immutable; event " + id + " cannot be deleted");
    }

    /**
     * Canonical serialisation that feeds the hash.
     *
     * <p>Field order and separators are part of the on-disk format: the verification endpoint
     * recomputes this exact string, so changing it invalidates every historical hash.</p>
     *
     * <p>{@code occurredAt} is encoded as epoch milliseconds rather than as
     * {@code Instant.toString()}. An {@code Instant} carries nanoseconds while a SQL {@code timestamp}
     * stores at most microseconds, so the textual form written and the textual form read back are not
     * the same string - and the chain would fail to verify even when nothing had been tampered
     * with.</p>
     */
    public String canonicalForm() {
        return String.join("|",
                nullSafe(sourceService),
                nullSafe(action),
                nullSafe(entityType),
                nullSafe(entityId),
                nullSafe(actorUsername),
                nullSafe(actorRole),
                nullSafe(operationId),
                nullSafe(correlationId),
                nullSafe(outcome),
                nullSafe(details),
                occurredAt == null ? "" : Long.toString(occurredAt.toEpochMilli()),
                nullSafe(previousHash));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
