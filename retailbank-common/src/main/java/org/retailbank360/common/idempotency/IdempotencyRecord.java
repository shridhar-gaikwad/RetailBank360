package org.retailbank360.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Remembers the outcome of a client-keyed operation so a replay returns the original result instead
 * of moving money twice.
 *
 * <p>The unique constraint on {@code (scope, idempotency_key)} is the actual concurrency control:
 * two simultaneous submissions of the same key race to insert, exactly one wins, and the loser is
 * told the request is already in flight.</p>
 */
@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_scope_key",
                columnNames = {"scope", "idempotency_key"}),
        indexes = @Index(name = "ix_idempotency_expires", columnList = "expires_at"))
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    public enum Status {
        /** A request holding this key is running right now. */
        IN_PROGRESS,
        /** The operation finished; {@link #responsePayload} holds the reply to replay. */
        COMPLETED,
        /** The operation failed in a way that makes a fresh attempt legitimate. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Operation family, so the same key may be reused for a transfer and for a repayment. */
    @Column(name = "scope", nullable = false, length = 60)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    /**
     * Fingerprint of the request body. A replay carrying the same key but a different payload is a
     * client bug and is rejected rather than silently answered with the first result.
     */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    /** JSON of the original response, replayed verbatim on a duplicate submission. */
    @Lob
    @Column(name = "response_payload")
    private String responsePayload;

    /** Business reference produced by the operation, e.g. a transaction reference. */
    @Column(name = "result_ref", length = 60)
    private String resultRef;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Retention boundary; records are purged after this to keep the table bounded. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
