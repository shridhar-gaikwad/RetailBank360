package org.retailbank360.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ConcurrentUpdateException;
import org.retailbank360.common.exception.DuplicateResourceException;
import org.retailbank360.common.exception.ExternalServiceException;
import org.retailbank360.common.exception.IdempotencyConflictException;
import org.retailbank360.common.exception.LockAcquisitionException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.exception.UnauthorizedOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Translates an error returned by another RetailBank360 service back into the same exception type it
 * was raised as.
 *
 * <p>Without this, a downstream "insufficient funds" (422) arrives as a generic {@code FeignException}
 * and the caller reports 500 - turning a perfectly ordinary business rejection into what looks like
 * an outage. Preserving the status and the error code means one rule is enforced in exactly one
 * place, and every caller still returns the right thing to its own client.</p>
 *
 * <p>Statuses that indicate a lost race - 409 and 423 - are rebuilt as the concurrency exceptions the
 * retry template recognises, so a deadlock downstream can still be retried upstream.</p>
 */
@Slf4j
public class RetailBankFeignErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;
    private final ErrorDecoder fallback = new Default();

    public RetailBankFeignErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        String service = response.request() == null ? "downstream service" : hostOf(response);
        Payload payload = readPayload(response);

        log.warn("{} returned {} for {}: [{}] {}", service, response.status(), methodKey,
                payload.errorCode(), payload.message());

        return switch (response.status()) {
            case 400, 422 -> new BusinessRuleViolationException(payload.errorCode(), payload.message());
            case 401, 403 -> new UnauthorizedOperationException(payload.message());
            case 404 -> new ResourceNotFoundException(payload.message());
            case 409 -> switch (payload.errorCode()) {
                case "DUPLICATE_RESOURCE" -> new DuplicateResourceException(payload.message());
                case "IDEMPOTENCY_CONFLICT" -> new IdempotencyConflictException(payload.message());
                default -> new ConcurrentUpdateException(payload.message(), null);
            };
            case 423 -> new LockAcquisitionException(payload.message(), service, 1);
            default -> {
                if (response.status() >= 500) {
                    yield new ExternalServiceException(service, payload.message());
                }
                yield fallback.decode(methodKey, response);
            }
        };
    }

    /** Reads the shared {@code ErrorResponse} body, falling back to a generic message. */
    private Payload readPayload(Response response) {
        if (response.body() == null) {
            return new Payload("DOWNSTREAM_ERROR", "HTTP " + response.status() + " with no response body");
        }
        try (InputStream body = response.body().asInputStream()) {
            String raw = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new Payload("DOWNSTREAM_ERROR", "HTTP " + response.status());
            }
            JsonNode node = objectMapper.readTree(raw);
            String code = node.hasNonNull("errorCode") ? node.get("errorCode").asText() : "DOWNSTREAM_ERROR";
            String message = node.hasNonNull("message") ? node.get("message").asText() : raw;
            return new Payload(code, message);
        } catch (IOException | RuntimeException e) {
            return new Payload("DOWNSTREAM_ERROR", "HTTP " + response.status() + " (unreadable body)");
        }
    }

    private static String hostOf(Response response) {
        try {
            return java.net.URI.create(response.request().url()).getHost();
        } catch (RuntimeException e) {
            return "downstream service";
        }
    }

    private record Payload(String errorCode, String message) {
    }
}
