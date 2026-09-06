package org.retailbank360.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Something a customer should be told about.
 *
 * <p>Carries a customer id, never an email address or a phone number. The publishing service has no
 * business handling contact details, so notification-service resolves them itself from
 * customer-service. That keeps PII out of transaction-service and loan-service entirely.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationEventRequest {

    @NotNull(message = "Customer id must not be null")
    private Long customerId;

    /** e.g. {@code TRANSFER_COMPLETED}, {@code LOAN_DISBURSED}. */
    @NotBlank(message = "Event type must not be blank")
    private String eventType;

    @NotBlank(message = "Subject must not be blank")
    @Size(max = 200, message = "Subject must not exceed 200 characters")
    private String subject;

    @NotBlank(message = "Message must not be blank")
    @Size(max = 2000, message = "Message must not exceed 2000 characters")
    private String message;

    /** Transaction or loan reference the notification relates to. */
    private String referenceId;

    private String sourceService;

    private String correlationId;
}
