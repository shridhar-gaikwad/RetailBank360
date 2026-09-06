package org.retailbank360.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operational view over the lock registry, mounted in every service that owns lockable resources.
 *
 * <p>It serves two audiences:</p>
 * <ul>
 *   <li><b>The UI.</b> {@code GET /api/v1/locks/status} lets a screen show "this account is being
 *       updated by another user" and disable the conflicting action <em>before</em> the operator
 *       submits, instead of surfacing a 423 afterwards.</li>
 *   <li><b>Administrators.</b> The list, the audit trail, a forced sweep, and a break-glass release
 *       for the rare case where a lock must be cleared before its TTL.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/locks")
public class LockAdminController {

    private final DistributedLockManager lockManager;
    private final LockAuditService auditService;

    public LockAdminController(DistributedLockManager lockManager, LockAuditService auditService) {
        this.lockManager = lockManager;
        this.auditService = auditService;
    }

    /** Every lock currently held on this cluster, optionally narrowed to one resource type. */
    @GetMapping
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<LockStatus>> heldLocks(
            @RequestParam(required = false) String resourceType) {
        return ResponseEntity.ok(lockManager.heldLocks(resourceType));
    }

    /**
     * State of a single resource. Polled by the UI to render a "resource in use" indicator and to
     * keep the operator from starting an operation that is bound to conflict.
     */
    @GetMapping("/status")
    @PreAuthorize(Roles.HAS_STAFF_OR_SERVICE)
    public ResponseEntity<LockStatus> status(@RequestParam String resourceType,
                                             @RequestParam String resourceId) {
        String key = resourceType + ":" + resourceId;
        return ResponseEntity.ok(lockManager.status(key)
                .orElseGet(() -> LockStatus.free(resourceType, resourceId)));
    }

    /** Lock acquisition and release history, filtered by key or by business operation. */
    @GetMapping("/audit")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<LockAuditView>> audit(
            @RequestParam(required = false) String lockKey,
            @RequestParam(required = false) String operationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var events = (operationId != null && !operationId.isBlank())
                ? auditService.historyForOperation(operationId, page, size)
                : auditService.history(lockKey, page, size);

        return ResponseEntity.ok(events.getContent().stream().map(LockAuditView::from).toList());
    }

    /** Forces an immediate orphan sweep instead of waiting for the scheduled one. */
    @PostMapping("/reap")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> reap() {
        int reclaimed = lockManager.reapOrphanedLocks();
        log.warn("Manual lock sweep triggered by {} reclaimed {} lock(s)",
                SecurityUtils.currentUsername(), reclaimed);
        return ResponseEntity.ok(Map.of(
                "node", lockManager.nodeId(),
                "reclaimed", reclaimed));
    }

    /**
     * Break-glass release. Intended for an operator dealing with a stuck resource whose TTL is long;
     * the action is recorded in the lock audit trail with the administrator username.
     */
    @DeleteMapping("/{resourceType}/{resourceId}")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> forceRelease(@PathVariable String resourceType,
                                                            @PathVariable String resourceId,
                                                            @RequestParam(required = false) String reason) {
        String key = resourceType + ":" + resourceId;
        boolean released = lockManager.forceRelease(key,
                reason == null ? "forced by administrator" : reason);
        log.warn("Administrator {} force-released lock {} (released={})",
                SecurityUtils.currentUsername(), key, released);
        return ResponseEntity.ok(Map.of("lockKey", key, "released", released));
    }
}
