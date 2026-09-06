package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Notification to deliver to a customer. */
@Data
public class NotificationRequest {

    private Long customerId;

    /** {@code EMAIL}, {@code SMS} or {@code PUSH}. */
    @NotBlank(message = "Channel must not be blank")
    private String channel;

    /** Masked before it is logged, so a recipient address never lands in a log file in full. */
    @NotBlank(message = "Recipient must not be blank")
    private String recipient;

    @NotBlank(message = "Subject must not be blank")
    @Size(max = 200, message = "Subject must not exceed 200 characters")
    private String subject;

    @NotBlank(message = "Message must not be blank")
    @Size(max = 2000, message = "Message must not exceed 2000 characters")
    private String message;

    /** Correlates the notification with the transaction or loan event that triggered it. */
    private String referenceId;
}
