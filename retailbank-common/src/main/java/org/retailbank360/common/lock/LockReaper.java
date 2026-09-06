package org.retailbank360.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.LockProperties;

import java.time.Instant;

/**
 * Background sweeper that reclaims locks whose owner died without releasing them, and trims the lock
 * audit trail to its retention window.
 *
 * <p>A contended lock recovers on its own, because the next caller takes over as soon as the TTL
 * lapses. This reaper covers the other case - a crash on a resource nobody is currently asking for -
 * so the lock table never accumulates permanently "held" rows, and so an administrator looking at
 * the lock view sees the truth rather than ghosts.</p>
 *
 * <p>The sweep is itself safe to run on every instance concurrently: reclaiming is a conditional
 * update keyed on the dead owner token, so at most one reaper can win per row.</p>
 */
@Slf4j
public class LockReaper {

    private final DistributedLockManager lockManager;
    private final LockAuditService auditService;
    private final LockProperties properties;

    public LockReaper(DistributedLockManager lockManager, LockAuditService auditService,
                      LockProperties properties) {
        this.lockManager = lockManager;
        this.auditService = auditService;
        this.properties = properties;
    }

    /**
     * Runs on the interval configured by {@code retailbank.lock.reaper-interval}.
     *
     * <p>Never lets an exception escape: a failed sweep must not kill the scheduler thread, and the
     * next tick will retry anyway.</p>
     */
    public void reap() {
        try {
            int reclaimed = lockManager.reapOrphanedLocks();
            if (reclaimed > 0) {
                log.warn("Lock reaper on node {} reclaimed {} orphaned lock(s)", lockManager.nodeId(), reclaimed);
            }
        } catch (RuntimeException e) {
            log.error("Lock reaper sweep failed; will retry on the next tick", e);
        }
    }

    /** Hourly trim of lock audit rows older than the configured retention. */
    public void purgeAudit() {
        try {
            Instant cutoff = Instant.now().minus(properties.getAuditRetention());
            int purged = auditService.purgeOlderThan(cutoff);
            if (purged > 0) {
                log.info("Purged {} lock audit event(s) older than {}", purged, cutoff);
            }
        } catch (RuntimeException e) {
            log.error("Lock audit purge failed; will retry on the next tick", e);
        }
    }
}
