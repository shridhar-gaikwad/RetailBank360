package org.retailbank360.service;

import org.retailbank360.dto.CustomerContactResponse;
import org.retailbank360.dto.CustomerProfileResponse;
import org.retailbank360.dto.CustomerRequest;
import org.retailbank360.dto.CustomerResponse;
import org.retailbank360.dto.KycDecisionRequest;

import java.util.List;

/** Customer master data and KYC lifecycle. */
public interface CustomerService {

    CustomerResponse createCustomer(CustomerRequest request);

    /** Bounded listing; the caller pages through rather than fetching the whole table. */
    List<CustomerResponse> getAllCustomers(int page, int size);

    CustomerResponse getCustomerById(Long customerId);

    CustomerResponse updateCustomer(Long customerId, CustomerRequest request);

    /**
     * Closes a customer.
     *
     * <p>Implemented as a status change rather than a row delete: accounts, ledger entries and loans
     * reference this customer, and a bank must be able to reconstruct history for a closed
     * relationship.</p>
     */
    void deleteCustomerById(Long customerId);

    CustomerResponse getCustomerByMobileNumber(String mobileNumber);

    CustomerResponse getCustomerByEmail(String email);

    List<CustomerResponse> searchCustomersByName(String name);

    /** Marks the KYC file verified, which is what unlocks account opening. */
    CustomerResponse verifyKyc(Long customerId, KycDecisionRequest request);

    CustomerResponse rejectKyc(Long customerId, KycDecisionRequest request);

    List<CustomerResponse> getPendingKyc();

    /** Internal projection used by account-service and loan-service. */
    CustomerProfileResponse getProfile(Long customerId);

    /**
     * Decrypted contact details, for notification-service only.
     *
     * <p>Separate from {@link #getProfile} on purpose: eligibility scoring has no business seeing
     * an email address, and delivery has no business seeing a credit score.</p>
     */
    CustomerContactResponse getContact(Long customerId);
}
