package org.retailbank360.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Writes and reads the lock audit trail.
 *
 * <p>Two rules shape this class. First, an audit row is written in its own {@code REQUIRES_NEW}
 * transaction, so the record of "user X locked account 42 for transfer T" survives even when the
 * business transaction it was protecting is rolled back - which is precisely the case an auditor
 * cares about. Second, a failure to write audit history is logged but never propagated: bookkeeping
 * must not be able to fail a banking operation.</p>
 */
@Slf4j
public class LockAuditService {

    private final LockAuditEventRepository repository;
    private final TransactionTemplate requiresNew;

    public LockAuditService(LockAuditEventRepository repository, TransactionTemplate requiresNew) {
        this.repository = repository;
        this.requiresNew = requiresNew;
    }

    public void record(LockAuditEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> repository.save(event));
        } catch (RuntimeException e) {
            log.error("Could not persist lock audit event {} for {}", event.getAction(), event.getLockKey(), e);
        }
    }

    public Page<LockAuditEvent> history(String lockKey, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return lockKey == null || lockKey.isBlank()
                ? repository.findAllByOrderByEventAtDesc(pageable)
                : repository.findByLockKeyOrderByEventAtDesc(lockKey, pageable);
    }

    public Page<LockAuditEvent> historyForOperation(String operationId, int page, int size) {
        return repository.findByOperationIdOrderByEventAtDesc(operationId, PageRequest.of(page, size));
    }

    /** Retention trim, driven by {@code retailbank.lock.audit-retention}. */
    public int purgeOlderThan(Instant cutoff) {
        Integer deleted = requiresNew.execute(status -> repository.deleteOlderThan(cutoff));
        return deleted == null ? 0 : deleted;
    }
}
