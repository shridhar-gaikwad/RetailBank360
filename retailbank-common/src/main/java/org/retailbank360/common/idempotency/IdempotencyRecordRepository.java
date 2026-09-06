package org.retailbank360.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Persistence for client-supplied idempotency keys. */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(String scope, String idempotencyKey);

    /**
     * Re-arms a previously failed key for a fresh attempt. Conditional on the current status so two
     * simultaneous retries cannot both start work.
     *
     * @return 1 when this caller claimed the key
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IdempotencyRecord r
               set r.status        = :newStatus,
                   r.requestHash   = :requestHash,
                   r.failureReason = null,
                   r.completedAt   = null,
                   r.expiresAt     = :expiresAt
             where r.scope = :scope
               and r.idempotencyKey = :key
               and r.status = :expectedStatus
            """)
    int reclaim(@Param("scope") String scope,
                @Param("key") String key,
                @Param("requestHash") String requestHash,
                @Param("expiresAt") Instant expiresAt,
                @Param("expectedStatus") IdempotencyRecord.Status expectedStatus,
                @Param("newStatus") IdempotencyRecord.Status newStatus);

    /** Retention trim for expired keys. */
    @Modifying(clearAutomatically = true)
    @Query("delete from IdempotencyRecord r where r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
