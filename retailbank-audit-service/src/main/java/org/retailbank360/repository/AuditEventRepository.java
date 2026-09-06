package org.retailbank360.repository;

import org.retailbank360.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read and append access to the audit trail.
 *
 * <p>No update or delete method is exposed, and the entity refuses both anyway. The only way to
 * correct the record is to append a further event describing the correction.</p>
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Tail of one service's chain, needed to compute the next hash.
     *
     * <p>The chain is sharded by source service. A single global chain would serialise every audit
     * write in the platform behind one lock; per-service chains give the same tamper evidence while
     * letting eight services append concurrently.</p>
     */
    Optional<AuditEvent> findFirstBySourceServiceOrderByIdDesc(String sourceService);

    /** One shard, in order, for verification. */
    List<AuditEvent> findBySourceServiceOrderByIdAsc(String sourceService);

    /** Every shard that exists, so verification knows what to walk. */
    @Query("select distinct e.sourceService from AuditEvent e order by e.sourceService")
    List<String> findSourceServices();

    List<AuditEvent> findByOperationIdOrderByIdAsc(String operationId);

    List<AuditEvent> findByCorrelationIdOrderByIdAsc(String correlationId);

    List<AuditEvent> findAllByOrderByIdAsc();

    /**
     * Filtered search backing the admin audit view. Every filter is optional; a null simply drops out
     * of the predicate, which keeps one query serving the whole screen.
     */
    @Query("""
            select e from AuditEvent e
             where (:action       is null or e.action = :action)
               and (:entityType   is null or e.entityType = :entityType)
               and (:entityId     is null or e.entityId = :entityId)
               and (:actor        is null or e.actorUsername = :actor)
               and (:outcome      is null or e.outcome = :outcome)
               and (:from         is null or e.occurredAt >= :from)
               and (:to           is null or e.occurredAt < :to)
             order by e.id desc
            """)
    Page<AuditEvent> search(@Param("action") String action,
                            @Param("entityType") String entityType,
                            @Param("entityId") String entityId,
                            @Param("actor") String actor,
                            @Param("outcome") String outcome,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);
}
