package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditEventRequest;
import org.retailbank360.common.lock.LockRequest;
import org.retailbank360.common.lock.LockTemplate;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.dto.AuditEventResponse;
import org.retailbank360.dto.ChainVerificationResponse;
import org.retailbank360.entity.AuditEvent;
import org.retailbank360.repository.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Stores and serves the audit trail.
 *
 * <h2>Why appending takes a lock, and why the chain is sharded</h2>
 * Each event hashes the hash of the one before it. That is only meaningful if events are appended in
 * a well-defined order, so the append runs under a resource lock. Two writers would otherwise both
 * read the same tail and produce two rows claiming the same predecessor, quietly breaking the chain.
 *
 * <p>The chain is sharded per source service - the lock is on {@code AUDIT_CHAIN:<service>}, not on a
 * single global key. A global chain would serialise every audit write in the platform behind one
 * lock, making the audit trail a bottleneck on the busiest path in the bank. Per-service chains give
 * exactly the same tamper evidence: altering or removing a row still breaks its own chain, and
 * verification simply walks each shard.</p>
 */
@Slf4j
@Service
public class AuditEventService {

    private static final String CHAIN_RESOURCE = "AUDIT_CHAIN";

    private final AuditEventRepository repository;
    private final AuditChainAppender chainAppender;
    private final LockTemplate lockTemplate;

    public AuditEventService(AuditEventRepository repository, AuditChainAppender chainAppender,
                             LockTemplate lockTemplate) {
        this.repository = repository;
        this.chainAppender = chainAppender;
        this.lockTemplate = lockTemplate;
    }

    /** Appends one event to its service's chain. */
    public AuditEventResponse record(AuditEventRequest request) {
        String shard = request.getSourceService() == null || request.getSourceService().isBlank()
                ? "unknown-service" : request.getSourceService();

        AuditEvent stored = lockTemplate.executeWithLock(
                LockRequest.of(CHAIN_RESOURCE, shard, request.getOperationId(),
                        Duration.ofSeconds(10), Duration.ofSeconds(10)),
                handle -> chainAppender.append(request));
        return AuditEventResponse.from(stored);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> search(String action, String entityType, String entityId, String actor,
                                           String outcome, Instant from, Instant to, int page, int size) {
        return repository.search(blankToNull(action), blankToNull(entityType), blankToNull(entityId),
                        blankToNull(actor), blankToNull(outcome), from, to, PageRequest.of(page, size))
                .map(AuditEventResponse::from);
    }

    /** Every event of one saga, in order: the view an investigator actually wants. */
    @Transactional(readOnly = true)
    public List<AuditEventResponse> byOperation(String operationId) {
        return repository.findByOperationIdOrderByIdAsc(operationId).stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> byCorrelation(String correlationId) {
        return repository.findByCorrelationIdOrderByIdAsc(correlationId).stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    /**
     * Walks every shard, recomputing each hash.
     *
     * <p>A mismatch means a row was altered after it was written, or that a row was removed. The id of
     * the first bad link in each shard is reported, which is where an investigation starts.</p>
     */
    @Transactional(readOnly = true)
    public ChainVerificationResponse verifyChain() {
        List<ChainVerificationResponse.ShardVerification> shards = new java.util.ArrayList<>();
        long totalChecked = 0;
        boolean allIntact = true;

        for (String sourceService : repository.findSourceServices()) {
            ChainVerificationResponse.ShardVerification shard = verifyShard(sourceService);
            shards.add(shard);
            totalChecked += shard.getEventsChecked();
            allIntact &= shard.isIntact();
        }

        return ChainVerificationResponse.builder()
                .intact(allIntact)
                .eventsChecked(totalChecked)
                .shardsChecked(shards.size())
                .shards(shards)
                .verifiedAt(Instant.now())
                .verifiedBy(SecurityUtils.currentUsername())
                .build();
    }

    private ChainVerificationResponse.ShardVerification verifyShard(String sourceService) {
        String expectedPrevious = null;
        long checked = 0;

        for (AuditEvent event : repository.findBySourceServiceOrderByIdAsc(sourceService)) {
            checked++;
            if (!java.util.Objects.equals(expectedPrevious, event.getPreviousHash())) {
                return brokenShard(sourceService, checked, event.getId(),
                        "Event " + event.getId() + " does not point at the previous event hash");
            }
            if (!sha256(event.canonicalForm()).equals(event.getEventHash())) {
                return brokenShard(sourceService, checked, event.getId(),
                        "Event " + event.getId() + " has been altered since it was recorded");
            }
            expectedPrevious = event.getEventHash();
        }

        return ChainVerificationResponse.ShardVerification.builder()
                .sourceService(sourceService)
                .intact(true)
                .eventsChecked(checked)
                .build();
    }

    /** CSV export of a search result, for the exportable audit-view requirement. */
    public String toCsv(List<AuditEventResponse> events) {
        StringBuilder csv = new StringBuilder(
                "Id,Occurred At,Source,Action,Entity Type,Entity Id,Actor,Role,Outcome,Operation Id,Details");
        csv.append(System.lineSeparator());
        for (AuditEventResponse event : events) {
            csv.append(event.getId()).append(',')
                    .append(event.getOccurredAt()).append(',')
                    .append(escape(event.getSourceService())).append(',')
                    .append(escape(event.getAction())).append(',')
                    .append(escape(event.getEntityType())).append(',')
                    .append(escape(event.getEntityId())).append(',')
                    .append(escape(event.getActorUsername())).append(',')
                    .append(escape(event.getActorRole())).append(',')
                    .append(escape(event.getOutcome())).append(',')
                    .append(escape(event.getOperationId())).append(',')
                    .append(escape(event.getDetails()))
                    .append(System.lineSeparator());
        }
        return csv.toString();
    }

    private ChainVerificationResponse.ShardVerification brokenShard(String sourceService, long checked,
                                                                    Long eventId, String problem) {
        log.error("Audit chain for {} failed verification at event {}: {}", sourceService, eventId, problem);
        return ChainVerificationResponse.ShardVerification.builder()
                .sourceService(sourceService)
                .intact(false)
                .eventsChecked(checked)
                .firstBrokenEventId(eventId)
                .problem(problem)
                .build();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Quotes a CSV field and neutralises the leading characters spreadsheets treat as formulas. */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        if ("=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
