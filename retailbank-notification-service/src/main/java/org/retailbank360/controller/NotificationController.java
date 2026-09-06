package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.dto.NotificationEventRequest;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.dto.NotificationRequest;
import org.retailbank360.dto.NotificationResponse;
import org.retailbank360.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Notification dispatch and the administrative view of what was sent. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Event endpoint used by the other services.
     *
     * <p>Takes a customer id and resolves the recipient here, so transaction-service and loan-service
     * never handle contact details.</p>
     */
    @PostMapping("/events")
    @PreAuthorize(Roles.HAS_STAFF_OR_SERVICE)
    public ResponseEntity<NotificationResponse> handleEvent(
            @Valid @RequestBody NotificationEventRequest event) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notificationService.handleEvent(event));
    }

    /** Direct send with an explicit recipient. Used by operations tooling, not by other services. */
    @PostMapping
    @PreAuthorize(Roles.HAS_STAFF_OR_SERVICE)
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notificationService.send(request));
    }

    @GetMapping
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<NotificationResponse>> recent(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(notificationService.recent(limit));
    }

    /** A customer may only list their own notifications. */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<NotificationResponse>> forCustomer(@PathVariable Long customerId) {
        SecurityUtils.requireCustomerAccess(customerId);
        return ResponseEntity.ok(notificationService.forCustomer(customerId));
    }
}
