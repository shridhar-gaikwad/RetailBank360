package org.retailbank360.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.exception.BusinessException;
import org.retailbank360.common.exception.IdempotencyConflictException;
import org.retailbank360.common.security.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a money-moving operation safe to retry.
 *
 * <p>The client sends a key it generated; the server records that key before doing any work and
 * stores the response against it afterwards. A replay - because of a timeout, a lost response, a
 * deadlock retry or an impatient user - returns the original result instead of posting a second
 * transaction.</p>
 *
 * <p>This is what makes the locking design safe to combine with retries: {@code LockTemplate} may
 * re-run a critical section after a deadlock precisely because the work underneath is idempotent.</p>
 *
 * <p>Bookkeeping runs in {@code REQUIRES_NEW} transactions so the key survives a rollback of the
 * business transaction, which is the whole point when the business transaction is the thing that
 * failed.</p>
 */
@Slf4j
public class IdempotencyService {

    /** How long a key is remembered; a replay after this behaves as a brand new request. */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper,
                              TransactionTemplate requiresNew) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNew = requiresNew;
    }

    /**
     * Runs {@code work} at most once for the given key.
     *
     * @param scope        operation family, e.g. {@code TRANSFER}
     * @param key          client-generated idempotency key; when blank the work simply runs
     * @param request      request object, fingerprinted to detect key reuse with a different payload
     * @param responseType type used to deserialize a replayed response
     * @return the fresh result, or the stored result of the original submission
     * @throws IdempotencyConflictException when the key is in flight or reused with a different body
     */
    public <T> T execute(String scope, String key, Object request, Class<T> responseType, Supplier<T> work) {
        if (key == null || key.isBlank()) {
            return work.get();
        }

        String requestHash = fingerprint(request);
        Optional<T> replayed = claim(scope, key, requestHash, responseType);
        if (replayed.isPresent()) {
            log.info("Replaying stored response for idempotency key {}:{}", scope, key);
            return replayed.get();
        }

        try {
            T result = work.get();
            complete(scope, key, result);
            return result;
        } catch (BusinessException e) {
            // A rejected request (insufficient funds, closed account) should not burn the key.
            markFailed(scope, key, e.getErrorCode() + ": " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            markFailed(scope, key, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Reserves the key for this caller.
     *
     * @return the stored response when this key has already completed, empty when the caller now
     *         owns the key and must do the work
     */
    private <T> Optional<T> claim(String scope, String key, String requestHash, Class<T> responseType) {
        Instant now = Instant.now();
        try {
            requiresNew.executeWithoutResult(status -> {
                IdempotencyRecord record = new IdempotencyRecord();
                record.setScope(scope);
                record.setIdempotencyKey(key);
                record.setRequestHash(requestHash);
                record.setStatus(IdempotencyRecord.Status.IN_PROGRESS);
                record.setCreatedBy(SecurityUtils.currentUsername());
                record.setCreatedAt(now);
                record.setExpiresAt(now.plus(RETENTION));
                repository.saveAndFlush(record);
            });
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            return handleExistingKey(scope, key, requestHash, responseType);
        }
    }

    private <T> Optional<T> handleExistingKey(String scope, String key, String requestHash, Class<T> responseType) {
        IdempotencyRecord existing = repository.findByScopeAndIdempotencyKey(scope, key)
                .orElseThrow(() -> new IdempotencyConflictException(
                        "Idempotency key " + key + " could not be reserved; retry the request"));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key " + key + " was already used for a different request payload");
        }

        return switch (existing.getStatus()) {
            case COMPLETED -> Optional.of(deserialize(existing.getResponsePayload(), responseType));
            case IN_PROGRESS -> throw new IdempotencyConflictException(
                    "A request with idempotency key " + key + " is still in progress. "
                            + "Wait for it to finish before retrying.");
            case FAILED -> {
                Instant now = Instant.now();
                Integer claimed = requiresNew.execute(status -> repository.reclaim(
                        scope, key, requestHash, now.plus(RETENTION),
                        IdempotencyRecord.Status.FAILED, IdempotencyRecord.Status.IN_PROGRESS));
                if (claimed == null || claimed != 1) {
                    throw new IdempotencyConflictException(
                            "A retry of idempotency key " + key + " is already in progress");
                }
                yield Optional.empty();
            }
        };
    }

    private void complete(String scope, String key, Object result) {
        requiresNew.executeWithoutResult(status ->
                repository.findByScopeAndIdempotencyKey(scope, key).ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.Status.COMPLETED);
                    record.setResponsePayload(serialize(result));
                    record.setResultRef(extractReference(result));
                    record.setCompletedAt(Instant.now());
                    repository.save(record);
                }));
    }

    private void markFailed(String scope, String key, String reason) {
        try {
            requiresNew.executeWithoutResult(status ->
                    repository.findByScopeAndIdempotencyKey(scope, key).ifPresent(record -> {
                        record.setStatus(IdempotencyRecord.Status.FAILED);
                        record.setFailureReason(truncate(reason));
                        record.setCompletedAt(Instant.now());
                        repository.save(record);
                    }));
        } catch (RuntimeException e) {
            log.error("Could not mark idempotency key {}:{} as failed", scope, key, e);
        }
    }

    /** SHA-256 of the canonical JSON of the request, so key reuse with a new payload is detectable. */
    private String fingerprint(Object request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request == null ? "" : request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint an idempotent request", e);
        }
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to store an idempotent response", e);
        }
    }

    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    /** Pulls a business reference out of the response for the admin view, when there is one. */
    private String extractReference(Object result) {
        if (result == null) {
            return null;
        }
        try {
            var node = objectMapper.valueToTree(result);
            for (String field : new String[]{"transactionRef", "reference", "loanRef", "entryRef"}) {
                if (node.hasNonNull(field)) {
                    return node.get(field).asText();
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not extract a reference from {}", result.getClass().getSimpleName());
        }
        return null;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** Hourly retention trim so the table stays bounded. */
    public void purgeExpired() {
        try {
            Integer deleted = requiresNew.execute(status -> repository.deleteExpired(Instant.now()));
            if (deleted != null && deleted > 0) {
                log.info("Purged {} expired idempotency record(s)", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Idempotency purge failed; will retry on the next tick", e);
        }
    }
}
