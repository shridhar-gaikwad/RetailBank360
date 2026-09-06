package org.retailbank360.common.lock;

import java.time.Instant;

/**
 * Audit row as exposed over the API.
 *
 * <p>Deliberately omits {@code ownerToken}: that value is the secret that authorises a release, and
 * publishing it would let a caller free somebody else's lock.</p>
 */
public record LockAuditView(
        Long id,
        String lockKey,
        String action,
        String node,
        String user,
        String operationId,
        Long fencingToken,
        Long heldMillis,
        String detail,
        Instant eventAt) {

    public static LockAuditView from(LockAuditEvent event) {
        return new LockAuditView(
                event.getId(),
                event.getLockKey(),
                event.getAction() == null ? null : event.getAction().name(),
                event.getOwnerNode(),
                event.getOwnerUser(),
                event.getOperationId(),
                event.getFencingToken(),
                event.getHeldMillis(),
                event.getDetail(),
                event.getEventAt());
    }
}
