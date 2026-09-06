package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.client.dto.CustomerContact;
import org.retailbank360.common.dto.NotificationEventRequest;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.dto.NotificationRequest;
import org.retailbank360.dto.NotificationResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Accepts and records notifications.
 *
 * <p>The buffer is bounded, so a long-running instance cannot grow without limit. Recipients are
 * masked before anything is logged or returned: an email address or a phone number in a log file is
 * a data leak, even when the message body is harmless.</p>
 */
@Slf4j
@Service
public class NotificationService {

    /** Most recent notifications kept for the admin view. */
    private static final int BUFFER_SIZE = 500;

    private final Deque<NotificationResponse> recent = new ArrayDeque<>();

    private final CustomerServiceClient customerServiceClient;

    public NotificationService(CustomerServiceClient customerServiceClient) {
        this.customerServiceClient = customerServiceClient;
    }

    /**
     * Handles an event published by another service.
     *
     * <p>The event carries a customer id, so the recipient is resolved here. A customer who cannot
     * be reached, or who is closed, is skipped rather than treated as an error: the banking
     * operation that triggered this has already committed.</p>
     */
    public NotificationResponse handleEvent(NotificationEventRequest event) {
        CustomerContact contact;
        try {
            contact = customerServiceClient.getContact(event.getCustomerId());
        } catch (RuntimeException e) {
            log.warn("Could not resolve contact details for customer {}; dropping {} notification: {}",
                    event.getCustomerId(), event.getEventType(), e.getMessage());
            return skipped(event, "RECIPIENT_UNRESOLVED");
        }
        if (contact == null || !contact.isActive()) {
            log.info("Customer {} is not contactable; dropping {} notification",
                    event.getCustomerId(), event.getEventType());
            return skipped(event, "CUSTOMER_NOT_CONTACTABLE");
        }

        NotificationRequest request = new NotificationRequest();
        request.setCustomerId(event.getCustomerId());
        // Email is the default channel; a real deployment would consult a per-customer preference.
        request.setChannel(contact.getEmail() != null && !contact.getEmail().isBlank() ? "EMAIL" : "SMS");
        request.setRecipient("EMAIL".equals(request.getChannel()) ? contact.getEmail() : contact.getPhone());
        request.setSubject(event.getSubject());
        request.setMessage(event.getMessage());
        request.setReferenceId(event.getReferenceId());
        return send(request);
    }

    /** Recorded so an operator can see that an event arrived but was not deliverable. */
    private NotificationResponse skipped(NotificationEventRequest event, String status) {
        NotificationResponse response = NotificationResponse.builder()
                .notificationId(IdGenerator.reference("NOTIF"))
                .customerId(event.getCustomerId())
                .channel("NONE")
                .subject(event.getSubject())
                .status(status)
                .referenceId(event.getReferenceId())
                .createdAt(Instant.now())
                .build();
        remember(response);
        return response;
    }

    public NotificationResponse send(NotificationRequest request) {
        NotificationResponse response = NotificationResponse.builder()
                .notificationId(IdGenerator.reference("NOTIF"))
                .customerId(request.getCustomerId())
                .channel(request.getChannel())
                .maskedRecipient(mask(request.getChannel(), request.getRecipient()))
                .subject(request.getSubject())
                .status("DELIVERED")
                .referenceId(request.getReferenceId())
                .createdAt(Instant.now())
                .build();

        // A real deployment swaps this for an email or SMS provider; the contract does not change.
        log.info("Notification {} via {} to {}: {}", response.getNotificationId(), response.getChannel(),
                response.getMaskedRecipient(), response.getSubject());

        remember(response);
        return response;
    }

    private void remember(NotificationResponse response) {
        synchronized (recent) {
            recent.addFirst(response);
            while (recent.size() > BUFFER_SIZE) {
                recent.removeLast();
            }
        }
    }

    public List<NotificationResponse> recent(int limit) {
        synchronized (recent) {
            return new ArrayList<>(recent).subList(0, Math.min(limit, recent.size()));
        }
    }

    public List<NotificationResponse> forCustomer(Long customerId) {
        synchronized (recent) {
            return recent.stream()
                    .filter(notification -> customerId.equals(notification.getCustomerId()))
                    .toList();
        }
    }

    private static String mask(String channel, String recipient) {
        return "EMAIL".equalsIgnoreCase(channel)
                ? MaskingUtil.maskEmail(recipient)
                : MaskingUtil.maskPhone(recipient);
    }
}
