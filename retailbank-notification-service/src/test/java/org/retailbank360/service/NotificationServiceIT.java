package org.retailbank360.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.client.dto.CustomerContact;
import org.retailbank360.common.dto.NotificationEventRequest;
import org.retailbank360.dto.NotificationRequest;
import org.retailbank360.dto.NotificationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Notification dispatch, and the proof that the shared module degrades cleanly.
 *
 * <p>This service has no JPA on its classpath at all. The locking and idempotency
 * auto-configurations in retailbank-common therefore must not activate here, while JWT security and
 * the shared error contract still must. A context that starts is the assertion: if a shared bean
 * were unconditionally wired, this is the module where it would blow up.</p>
 */
@SpringBootTest
class NotificationServiceIT {

    @MockitoBean
    private CustomerServiceClient customerServiceClient;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("The context starts without a database, and the JPA-only shared beans stay absent")
    void startsWithoutADatabase() {
        assertThat(applicationContext.containsBean("lockTemplate"))
                .as("the locking framework needs JPA, which this service does not have")
                .isFalse();
        assertThat(applicationContext.containsBean("idempotencyService")).isFalse();
        // The pieces that need nothing but spring-web are still there.
        assertThat(applicationContext.getBean(org.retailbank360.common.security.JwtTokenService.class))
                .isNotNull();
    }

    @Test
    @DisplayName("A recipient address is masked before it is stored or logged")
    void masksTheRecipient() {
        NotificationResponse email = notificationService.send(
                request("EMAIL", "john.doe@example.com", "Transfer completed"));
        NotificationResponse sms = notificationService.send(
                request("SMS", "9876543210", "Transfer completed"));

        assertThat(email.getMaskedRecipient()).isEqualTo("jo***@example.com");
        assertThat(sms.getMaskedRecipient()).isEqualTo("XXXXXX3210");
        assertThat(email.getNotificationId()).startsWith("NOTIF-");
        assertThat(email.getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("Notifications can be listed back, newest first and scoped to a customer")
    void listsRecentNotifications() {
        notificationService.send(request("EMAIL", "a.customer@example.com", "First"));
        notificationService.send(request("EMAIL", "a.customer@example.com", "Second"));

        assertThat(notificationService.recent(10)).isNotEmpty();
        assertThat(notificationService.recent(10).get(0).getSubject()).isEqualTo("Second");
        assertThat(notificationService.forCustomer(7L))
                .isNotEmpty()
                .allSatisfy(notification -> assertThat(notification.getCustomerId()).isEqualTo(7L));
    }

    @Test
    @DisplayName("An event carries only a customer id; the recipient is resolved here")
    void resolvesTheRecipientFromAnEvent() {
        when(customerServiceClient.getContact(7L)).thenReturn(
                new CustomerContact(7L, "Asha Rao", "asha.rao@example.com", "9876500011", true));

        NotificationResponse response = notificationService.handleEvent(event());

        assertThat(response.getStatus()).isEqualTo("DELIVERED");
        assertThat(response.getChannel()).isEqualTo("EMAIL");
        // The publishing service sent no address at all, and the one resolved here is masked.
        assertThat(response.getMaskedRecipient()).isEqualTo("as***@example.com");
    }

    @Test
    @DisplayName("An unreachable customer-service drops the notification, it does not throw")
    void dropsTheNotificationWhenTheRecipientCannotBeResolved() {
        when(customerServiceClient.getContact(7L)).thenThrow(new IllegalStateException("connection refused"));

        // The banking operation that triggered this has already committed; failing here would
        // achieve nothing except noise.
        assertThat(notificationService.handleEvent(event()).getStatus())
                .isEqualTo("RECIPIENT_UNRESOLVED");
    }

    @Test
    @DisplayName("A closed customer is not contacted")
    void skipsAClosedCustomer() {
        when(customerServiceClient.getContact(7L)).thenReturn(
                new CustomerContact(7L, "Asha Rao", "asha.rao@example.com", "9876500011", false));

        assertThat(notificationService.handleEvent(event()).getStatus())
                .isEqualTo("CUSTOMER_NOT_CONTACTABLE");
    }

    private NotificationEventRequest event() {
        return NotificationEventRequest.builder()
                .customerId(7L)
                .eventType("TRANSACTION_TRANSFER")
                .referenceId("TXN-1")
                .subject("Transfer completed")
                .message("250.00 INR was transferred from your account.")
                .build();
    }

    private NotificationRequest request(String channel, String recipient, String subject) {
        NotificationRequest request = new NotificationRequest();
        request.setCustomerId(7L);
        request.setChannel(channel);
        request.setRecipient(recipient);
        request.setSubject(subject);
        request.setMessage("Your transfer of 100.00 INR has completed.");
        request.setReferenceId("TXN-1");
        return request;
    }
}
