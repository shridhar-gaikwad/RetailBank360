package org.retailbank360.common.lock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Read and retention queries over the append-only lock audit trail. */
@Repository
public interface LockAuditEventRepository extends JpaRepository<LockAuditEvent, Long> {

    Page<LockAuditEvent> findByLockKeyOrderByEventAtDesc(String lockKey, Pageable pageable);

    Page<LockAuditEvent> findByOperationIdOrderByEventAtDesc(String operationId, Pageable pageable);

    List<LockAuditEvent> findByOwnerUserOrderByEventAtDesc(String ownerUser, Pageable pageable);

    Page<LockAuditEvent> findAllByOrderByEventAtDesc(Pageable pageable);

    /**
     * Retention trim. Bulk delete on purpose: it bypasses the entity callbacks that make individual
     * rows immutable, which is the only sanctioned way to remove audit history.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from LockAuditEvent e where e.eventAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
