package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.dto.CustomerContactResponse;
import org.retailbank360.dto.CustomerProfileResponse;
import org.retailbank360.dto.CustomerRequest;
import org.retailbank360.dto.CustomerResponse;
import org.retailbank360.dto.KycDecisionRequest;
import org.retailbank360.service.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer master data and KYC endpoints.
 *
 * <p>Role rules: only staff may create, list or search customers; a {@code CUSTOMER} principal may
 * read and update its own record, which is enforced per row inside the service because a role check
 * alone cannot express "your own". KYC decisions are restricted to tellers and administrators.</p>
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.createCustomer(request));
    }

    @GetMapping("/all")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<List<CustomerResponse>> getAllCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(customerService.getAllCustomers(page, size));
    }

    /** Readable by staff, and by the customer it belongs to. */
    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getCustomerById(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getCustomerById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable Long id,
                                                           @Valid @RequestBody CustomerRequest request) {
        return ResponseEntity.ok(customerService.updateCustomer(id, request));
    }

    /** Closes the relationship. History is retained, so this is a status change, not a row delete. */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Void> deleteCustomerById(@PathVariable Long id) {
        customerService.deleteCustomerById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mobile/{mobileNumber}")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<CustomerResponse> getCustomerByMobileNumber(@PathVariable String mobileNumber) {
        return ResponseEntity.ok(customerService.getCustomerByMobileNumber(mobileNumber));
    }

    @GetMapping("/email/{email}")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<CustomerResponse> getCustomerByEmail(@PathVariable String email) {
        return ResponseEntity.ok(customerService.getCustomerByEmail(email));
    }

    /** Partial, case-insensitive match on first or last name. */
    @GetMapping("/name/{name}")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<List<CustomerResponse>> getCustomerByName(@PathVariable String name) {
        return ResponseEntity.ok(customerService.searchCustomersByName(name));
    }

    @GetMapping("/kyc/pending")
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<List<CustomerResponse>> getPendingKyc() {
        return ResponseEntity.ok(customerService.getPendingKyc());
    }

    /** Approves the KYC file, which is the precondition for opening an account. */
    @PostMapping("/{id}/kyc/verify")
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<CustomerResponse> verifyKyc(@PathVariable Long id,
                                                      @Valid @RequestBody KycDecisionRequest request) {
        return ResponseEntity.ok(customerService.verifyKyc(id, request));
    }

    @PostMapping("/{id}/kyc/reject")
    @PreAuthorize(Roles.HAS_TELLER_OR_ADMIN)
    public ResponseEntity<CustomerResponse> rejectKyc(@PathVariable Long id,
                                                      @Valid @RequestBody KycDecisionRequest request) {
        return ResponseEntity.ok(customerService.rejectKyc(id, request));
    }

    /**
     * Internal projection for account-service (KYC gate) and loan-service (eligibility scoring).
     * Requires a service principal, so it cannot be called with an end-user token.
     */
    @GetMapping("/internal/{id}/profile")
    @PreAuthorize(Roles.HAS_STAFF_OR_SERVICE)
    public ResponseEntity<CustomerProfileResponse> getProfile(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getProfile(id));
    }

    /**
     * Contact details for notification-service.
     *
     * <p>Restricted to a service principal: this is the only endpoint that returns decrypted contact
     * data, so it is not reachable with a staff or customer token.</p>
     */
    @GetMapping("/internal/{id}/contact")
    @PreAuthorize(Roles.HAS_SERVICE)
    public ResponseEntity<CustomerContactResponse> getContact(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getContact(id));
    }
}
