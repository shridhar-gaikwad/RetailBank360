package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.dto.AuditEventResponse;
import org.retailbank360.dto.ChainVerificationResponse;
import org.retailbank360.service.AuditEventService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Audit ingestion and the administrative audit views.
 *
 * <p>Writing is restricted to internal service principals; reading, exporting and chain verification
 * to administrators. There is no update or delete endpoint at all - correcting the record means
 * appending a further event, never editing history.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditEventService auditEventService;

    public AuditController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    /** Ingestion endpoint. Called by the shared {@code AuditPublisher} in every other service. */
    @PostMapping("/events")
    @PreAuthorize(Roles.HAS_STAFF_OR_SERVICE)
    public ResponseEntity<AuditEventResponse> record(@Valid @RequestBody AuditEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auditEventService.record(request));
    }

    /** Filtered audit view. Every filter is optional. */
    @GetMapping("/events")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<AuditEventResponse>> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(auditEventService.search(action, entityType, entityId, actor, outcome,
                startOfDay(from), startOfNextDay(to), page, size).getContent());
    }

    /** Every event of one business operation, in order. */
    @GetMapping("/events/operation/{operationId}")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<AuditEventResponse>> byOperation(@PathVariable String operationId) {
        return ResponseEntity.ok(auditEventService.byOperation(operationId));
    }

    /** Every event that shares one correlation id, i.e. one request across all services. */
    @GetMapping("/events/correlation/{correlationId}")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<AuditEventResponse>> byCorrelation(@PathVariable String correlationId) {
        return ResponseEntity.ok(auditEventService.byCorrelation(correlationId));
    }

    /** Same search, rendered as a downloadable CSV. */
    @GetMapping(value = "/events/export", produces = "text/csv")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<String> export(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "5000") int limit) {

        List<AuditEventResponse> events = auditEventService.search(action, entityType, entityId, actor,
                outcome, startOfDay(from), startOfNextDay(to), 0, limit).getContent();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-events.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(auditEventService.toCsv(events));
    }

    /**
     * Recomputes the whole hash chain and reports whether any historical row has been altered.
     *
     * <p>This is what turns "we store an audit trail" into "we can prove the audit trail has not been
     * edited".</p>
     */
    @GetMapping("/verify")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<ChainVerificationResponse> verify() {
        return ResponseEntity.ok(auditEventService.verifyChain());
    }

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** Exclusive upper bound, so the whole of {@code to} is included in the range. */
    private static Instant startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
