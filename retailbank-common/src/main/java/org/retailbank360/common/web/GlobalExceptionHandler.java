package org.retailbank360.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.dto.ErrorResponse;
import org.retailbank360.common.exception.BusinessException;
import org.retailbank360.common.exception.LockAcquisitionException;
import org.retailbank360.common.exception.RateLimitExceededException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Translates exceptions into the single {@link ErrorResponse} shape used by every service.
 *
 * <p>Lives in the shared module so that a 423 from a lock conflict, a 409 from an optimistic lock
 * clash and a 422 from a business rule look identical no matter which microservice produced them.</p>
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class GlobalExceptionHandler {

    /** Every expected domain failure carries its own status and error code. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("{} on {}: {}", ex.getErrorCode(), request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.warn("{} on {}: {}", ex.getErrorCode(), request.getRequestURI(), ex.getMessage());
        }

        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof LockAcquisitionException lockEx) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(lockEx.getRetryAfterSeconds()));
        } else if (ex instanceof RateLimitExceededException rateEx) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(rateEx.getRetryAfterSeconds()));
        }

        return ResponseEntity.status(ex.getStatus())
                .headers(headers)
                .body(body(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex,
                                                           HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        log.warn("Validation failed on {}: {}", request.getRequestURI(), details);
        return ResponseEntity.badRequest().body(validationBody(details, request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
        log.warn("Constraint violation on {}: {}", request.getRequestURI(), details);
        return ResponseEntity.badRequest().body(validationBody(details, request));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("Malformed request on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ex.getMessage(), request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                        "You do not have the role required for this operation", request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(body(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", ex.getMessage(), request));
    }

    @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleNoHandler(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", ex.getMessage(), request));
    }

    /** Last resort. The message is deliberately generic so internals never leak to a client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "An unexpected error occurred. Quote the correlation id when reporting it.", request));
    }

    private static ErrorResponse validationBody(List<String> details, HttpServletRequest request) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .errorCode("VALIDATION_FAILED")
                .message("Request validation failed")
                .path(request.getRequestURI())
                .correlationId(CorrelationIdFilter.currentCorrelationId())
                .details(details)
                .build();
    }

    private static ErrorResponse body(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ErrorResponse.of(status.value(), status.getReasonPhrase(), code, message,
                request.getRequestURI(), CorrelationIdFilter.currentCorrelationId());
    }
}
