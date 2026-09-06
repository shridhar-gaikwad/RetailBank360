package org.retailbank360.common.lock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per lockable resource, e.g. {@code ACCOUNT:42} or {@code LOAN:7}.
 *
 * <p>The row is created on first use and then <em>reused</em> rather than deleted on release: the
 * owner columns are simply cleared. Keeping the row is what allows {@link #fencingToken} to increase
 * monotonically for the lifetime of the resource, which in turn lets a writer prove it still holds
 * the lock it thinks it holds (see {@code LockHandle}).</p>
 *
 * <p>Granularity is deliberately per resource id, never per table and never per database, so two
 * tellers working on two different accounts never block each other.</p>
 */
@Entity
@Table(name = "resource_locks",
        uniqueConstraints = @UniqueConstraint(name = "uk_resource_locks_key", columnNames = "lock_key"),
        indexes = {
                @Index(name = "ix_resource_locks_expires", columnList = "expires_at"),
                @Index(name = "ix_resource_locks_owner", columnList = "owner_token")
        })
@Getter
@Setter
@NoArgsConstructor
public class ResourceLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical key, {@code resourceType:resourceId}. Unique, and the only column ever matched on. */
    @Column(name = "lock_key", nullable = false, unique = true, length = 200)
    private String lockKey;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 120)
    private String resourceId;

    /** {@code null} means the lock is free. Otherwise {@code nodeId:uuid} of the current holder. */
    @Column(name = "owner_token", length = 120)
    private String ownerToken;

    /** Which service instance holds it, so an operator can tell two replicas apart. */
    @Column(name = "owner_node", length = 120)
    private String ownerNode;

    /** Who triggered the operation, for the audit trail. */
    @Column(name = "owner_user", length = 120)
    private String ownerUser;

    /** Business operation the lock protects, e.g. a transfer reference or an idempotency key. */
    @Column(name = "operation_id", length = 120)
    private String operationId;

    @Column(name = "acquired_at")
    private Instant acquiredAt;

    /**
     * TTL boundary. Once passed, any other caller may take the lock over, which is how a lock left
     * behind by a crashed process is recovered without operator intervention.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    /** Increases on every successful acquisition. Never reset while the row exists. */
    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    /** Total number of times this resource has ever been locked. Useful for hot-spot analysis. */
    @Column(name = "acquire_count", nullable = false)
    private long acquireCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** True when the lock is currently held and has not yet timed out. */
    public boolean isHeld(Instant now) {
        return ownerToken != null && expiresAt != null && expiresAt.isAfter(now);
    }

    /** True when a holder exists on paper but its TTL has lapsed, i.e. an orphaned lock. */
    public boolean isOrphaned(Instant now) {
        return ownerToken != null && (expiresAt == null || !expiresAt.isAfter(now));
    }

    public static ResourceLock forKey(String resourceType, String resourceId) {
        ResourceLock lock = new ResourceLock();
        lock.setResourceType(resourceType);
        lock.setResourceId(resourceId);
        lock.setLockKey(resourceType + ":" + resourceId);
        lock.setCreatedAt(Instant.now());
        lock.setFencingToken(0L);
        lock.setAcquireCount(0L);
        return lock;
    }
}
