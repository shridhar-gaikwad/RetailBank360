package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Record of a notification that was accepted for delivery. */
@Data
@Builder
public class NotificationResponse {

    private String notificationId;

    private Long customerId;

    private String channel;

    /** Recipient, masked. The full address is never returned or logged. */
    private String maskedRecipient;

    private String subject;

    private String status;

    private String referenceId;

    private Instant createdAt;
}
