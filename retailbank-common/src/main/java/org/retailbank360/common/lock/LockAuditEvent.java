package org.retailbank360.common.lock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only record of every lock lifecycle event: who took which resource, when, for which
 * operation, and how it ended.
 *
 * <p>Required by the specification ("record lock acquisition/release events with user id, timestamp,
 * and operation id") and genuinely useful in production: when a teller reports "the screen said the
 * account was in use", this table says exactly which instance held it and why.</p>
 *
 * <p>Updates and deletes are rejected by the lifecycle callbacks below; the reaper trims old rows
 * with a bulk query, which deliberately bypasses those callbacks.</p>
 */
@Entity
@Table(name = "resource_lock_audit",
        indexes = {
                @Index(name = "ix_lock_audit_key", columnList = "lock_key"),
                @Index(name = "ix_lock_audit_at", columnList = "event_at"),
                @Index(name = "ix_lock_audit_operation", columnList = "operation_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class LockAuditEvent {

    public enum Action {
        /** Lock taken on a free resource. */
        ACQUIRED,
        /** Lock taken over from a holder whose TTL had lapsed (crash recovery). */
        STOLEN,
        /** Nested acquisition by a caller that already holds the lock. */
        REENTERED,
        /** Caller gave up after exhausting its wait budget. */
        WAIT_TIMEOUT,
        /** Normal release by the owner. */
        RELEASED,
        /** TTL extended while work was still in progress. */
        RENEWED,
        /** Owner tried to release but no longer held the lock. */
        LOST,
        /** Background reaper reclaimed an orphaned lock. */
        REAPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lock_key", nullable = false, length = 200)
    private String lockKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private Action action;

    @Column(name = "owner_token", length = 120)
    private String ownerToken;

    @Column(name = "owner_node", length = 120)
    private String ownerNode;

    /** User id / username on whose behalf the lock was taken. */
    @Column(name = "owner_user", length = 120)
    private String ownerUser;

    @Column(name = "operation_id", length = 120)
    private String operationId;

    @Column(name = "fencing_token")
    private Long fencingToken;

    /** How long the lock was held, in milliseconds. Only set on terminal events. */
    @Column(name = "held_millis")
    private Long heldMillis;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "event_at", nullable = false, updatable = false)
    private Instant eventAt;

    @PreUpdate
    @PreRemove
    private void rejectMutation() {
        throw new UnsupportedOperationException("resource_lock_audit is append-only");
    }

    public static LockAuditEvent of(String lockKey, Action action, String ownerToken, String ownerNode,
                                    String ownerUser, String operationId, Long fencingToken, String detail) {
        LockAuditEvent event = new LockAuditEvent();
        event.setLockKey(lockKey);
        event.setAction(action);
        event.setOwnerToken(ownerToken);
        event.setOwnerNode(ownerNode);
        event.setOwnerUser(ownerUser);
        event.setOperationId(operationId);
        event.setFencingToken(fencingToken);
        event.setDetail(detail);
        event.setEventAt(Instant.now());
        return event;
    }
}
