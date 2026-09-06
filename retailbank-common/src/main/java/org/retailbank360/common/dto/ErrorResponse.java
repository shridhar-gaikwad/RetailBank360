package org.retailbank360.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** Uniform error payload returned by every RetailBank360 REST endpoint. */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final LocalDateTime timestamp;

    private final int status;

    private final String error;

    /** Stable machine readable code, e.g. {@code RESOURCE_LOCKED}. */
    private final String errorCode;

    private final String message;

    private final String path;

    /** Correlation id echoed from the request, useful when tracing a saga across services. */
    private final String correlationId;

    /** Field level messages produced by bean validation. */
    private final List<String> details;

    public static ErrorResponse of(int status, String error, String errorCode, String message, String path,
                                   String correlationId) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .error(error)
                .errorCode(errorCode)
                .message(message)
                .path(path)
                .correlationId(correlationId)
                .build();
    }
}
