package org.retailbank360.common.web;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.dto.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns raw persistence failures into the same {@link ErrorResponse} contract as the domain
 * exceptions, and - importantly for this POC - gives concurrency failures a distinct, actionable
 * meaning instead of a blanket 500:
 *
 * <ul>
 *   <li>optimistic version clash -&gt; 409 {@code CONCURRENT_MODIFICATION} (reload and retry)</li>
 *   <li>database deadlock / lock wait timeout -&gt; 409 {@code DEADLOCK_DETECTED} (safe to replay
 *       with the same idempotency key)</li>
 *   <li>row still pessimistically locked -&gt; 423 {@code RESOURCE_LOCKED} with {@code Retry-After}</li>
 *   <li>unique constraint clash -&gt; 409 {@code DUPLICATE_RESOURCE}</li>
 * </ul>
 *
 * <p>Registered only when JPA is on the classpath, so the DB-less services are unaffected.</p>
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class DataAccessExceptionHandler {

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                        "The record was modified by another user while you were editing it. "
                                + "Reload the latest version and retry.", request));
    }

    @ExceptionHandler({DeadlockLoserDataAccessException.class, CannotAcquireLockException.class})
    public ResponseEntity<ErrorResponse> handleDeadlock(Exception ex, HttpServletRequest request) {
        log.warn("Deadlock or lock wait timeout on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body(HttpStatus.CONFLICT, "DEADLOCK_DETECTED",
                        "The operation conflicted with another concurrent operation and was rolled back. "
                                + "No money was moved. Retry with the same idempotency key.", request));
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, PessimisticLockException.class,
            QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> handlePessimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("Could not obtain row lock on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.LOCKED)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body(HttpStatus.LOCKED, "RESOURCE_LOCKED",
                        "The record is currently locked by another operation. Please retry shortly.", request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                          HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE",
                        "The request violates a uniqueness or referential constraint", request));
    }

    private static ErrorResponse body(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ErrorResponse.of(status.value(), status.getReasonPhrase(), code, message,
                request.getRequestURI(), CorrelationIdFilter.currentCorrelationId());
    }
}
