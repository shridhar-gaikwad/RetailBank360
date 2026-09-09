package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.crypto.CryptoHolder;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.DuplicateResourceException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.lock.LockRequest;
import org.retailbank360.common.lock.LockResourceTypes;
import org.retailbank360.common.lock.LockTemplate;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.web.PageRequests;
import org.retailbank360.constants.CustomerStatus;
import org.retailbank360.constants.KycStatus;
import org.retailbank360.dto.CustomerContactResponse;
import org.retailbank360.dto.CustomerPatchRequest;
import org.retailbank360.dto.CustomerProfileResponse;
import org.retailbank360.dto.CustomerRequest;
import org.retailbank360.dto.CustomerResponse;
import org.retailbank360.dto.KycDecisionRequest;
import org.retailbank360.entity.Customer;
import org.retailbank360.mapper.CustomerMapper;
import org.retailbank360.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * Customer master data, KYC transitions and the internal profile other services score against.
 *
 * <p>KYC decisions are taken under a resource lock on the customer, so a teller approving and an
 * administrator rejecting at the same moment cannot interleave into a half-applied state. Ordinary
 * profile edits rely on the {@code @Version} column instead: a clash there is rare, and the right
 * answer is to tell the second editor to reload rather than to make them queue.</p>
 */
@Slf4j
@Service
public class CustomerServiceImpl implements CustomerService {

    /** Minimum age for opening a banking relationship. */
    private static final int MINIMUM_AGE_YEARS = 18;

    private final CustomerRepository customerRepository;
    private final KycTransactionService kycTransactionService;
    private final LockTemplate lockTemplate;
    private final AuditPublisher auditPublisher;
    private final CustomerMapper customerMapper;

    public CustomerServiceImpl(CustomerRepository customerRepository,
                               KycTransactionService kycTransactionService,
                               LockTemplate lockTemplate,
                               AuditPublisher auditPublisher,
                               CustomerMapper customerMapper) {
        this.customerRepository = customerRepository;
        this.kycTransactionService = kycTransactionService;
        this.lockTemplate = lockTemplate;
        this.auditPublisher = auditPublisher;
        this.customerMapper = customerMapper;
    }

    @Override
    @Transactional
    public CustomerResponse createCustomer(CustomerRequest request) {
        validateAge(request.getDateOfBirth());
        assertContactIsFree(request.getEmail(), request.getPhone(), null);

        Customer customer = new Customer();
        applyRequest(customer, request);
        customer.setCustomerNumber(request.getCustomerNumber() == null || request.getCustomerNumber().isBlank()
                ? generateCustomerNumber()
                : request.getCustomerNumber());
        customer.setStatus(CustomerStatus.ACTIVE);
        // A brand new customer is always unverified: KYC is a separate, audited decision.
        customer.setKycStatus(KycStatus.PENDING);

        Customer saved = customerRepository.save(customer);
        log.info("Created customer {} ({})", saved.getCustomerNumber(), saved.getId());
        auditPublisher.publishSuccess(AuditActions.CUSTOMER_CREATED, "CUSTOMER", saved.getId(), null);
        return CustomerResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponse> getAllCustomers(int page, int size) {
        return customerRepository.findAll(PageRequests.of(page, size))
                .map(CustomerResponse::from)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getCustomerById(Long customerId) {
        Customer customer = requireCustomer(customerId);
        // A customer principal may only read its own record; staff and services are unrestricted.
        SecurityUtils.requireCustomerAccess(customer.getId());
        return CustomerResponse.from(customer);
    }

    @Override
    @Transactional
    public CustomerResponse updateCustomer(Long customerId, CustomerRequest request) {
        Customer customer = requireCustomer(customerId);
        SecurityUtils.requireCustomerAccess(customer.getId());

        if (customer.getStatus() == CustomerStatus.CLOSED) {
            throw new BusinessRuleViolationException("Customer " + customerId + " is closed and cannot be edited");
        }
        validateAge(request.getDateOfBirth());
        assertContactIsFree(request.getEmail(), request.getPhone(), customerId);

        applyRequest(customer, request);
        Customer saved = customerRepository.save(customer);
        log.info("Updated customer {}", customerId);
        auditPublisher.publishSuccess(AuditActions.CUSTOMER_UPDATED, "CUSTOMER", customerId, null);
        return CustomerResponse.from(saved);
    }

    @Override
    @Transactional
    public CustomerResponse patchCustomer(Long customerId, CustomerPatchRequest request) {
        Customer customer = requireCustomer(customerId);
        SecurityUtils.requireCustomerAccess(customer.getId());

        if (customer.getStatus() == CustomerStatus.CLOSED) {
            throw new BusinessRuleViolationException("Customer " + customerId + " is closed and cannot be edited");
        }
        // Both guards below no-op when the field is absent, so an untouched contact or DOB is fine.
        if (request.getDateOfBirth() != null) {
            validateAge(request.getDateOfBirth());
        }
        assertContactIsFree(request.getEmail(), request.getPhone(), customerId);

        customerMapper.updateFromPatch(request, customer);
        Customer saved = customerRepository.save(customer);
        log.info("Patched customer {}", customerId);
        auditPublisher.publishSuccess(AuditActions.CUSTOMER_UPDATED, "CUSTOMER", customerId, null);
        return CustomerResponse.from(saved);
    }

    @Override
    @Transactional
    public void deleteCustomerById(Long customerId) {
        Customer customer = requireCustomer(customerId);
        if (customer.getStatus() == CustomerStatus.CLOSED) {
            return;
        }
        customer.setStatus(CustomerStatus.CLOSED);
        customerRepository.save(customer);
        log.info("Closed customer {}", customerId);
        auditPublisher.publishSuccess(AuditActions.CUSTOMER_DELETED, "CUSTOMER", customerId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getCustomerByMobileNumber(String mobileNumber) {
        Customer customer = customerRepository.findByPhoneIndex(CryptoHolder.blindIndex(mobileNumber))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No customer found for that phone number"));
        SecurityUtils.requireCustomerAccess(customer.getId());
        return CustomerResponse.from(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getCustomerByEmail(String email) {
        Customer customer = customerRepository.findByEmailIndex(CryptoHolder.blindIndex(email))
                .orElseThrow(() -> new ResourceNotFoundException("No customer found for that email address"));
        SecurityUtils.requireCustomerAccess(customer.getId());
        return CustomerResponse.from(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponse> searchCustomersByName(String name) {
        return customerRepository.searchByName(name).stream()
                .filter(customer -> !SecurityUtils.isCustomerPrincipal()
                        || SecurityUtils.currentUser()
                        .map(user -> customer.getId().equals(user.customerId()))
                        .orElse(false))
                .map(CustomerResponse::from)
                .toList();
    }

    @Override
    public CustomerResponse verifyKyc(Long customerId, KycDecisionRequest request) {
        return decideKyc(customerId, KycStatus.VERIFIED, request.getRemarks());
    }

    @Override
    public CustomerResponse rejectKyc(Long customerId, KycDecisionRequest request) {
        return decideKyc(customerId, KycStatus.REJECTED, request.getRemarks());
    }

    /**
     * Applies a KYC decision under a resource lock.
     *
     * <p>The lock is taken outside the transaction and the row is then re-read with a
     * {@code PESSIMISTIC_WRITE} lock inside it, so neither another instance of this service nor
     * another transaction on this instance can interleave.</p>
     */
    private CustomerResponse decideKyc(Long customerId, KycStatus decision, String remarks) {
        String operationId = "kyc-" + decision.name().toLowerCase() + "-" + customerId;

        return lockTemplate.executeWithLock(
                LockRequest.of(LockResourceTypes.CUSTOMER, customerId, operationId),
                handle -> kycTransactionService.applyDecision(customerId, decision, remarks));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponse> getPendingKyc() {
        return customerRepository.findByKycStatus(KycStatus.PENDING,
                        org.springframework.data.domain.PageRequest.of(0, 200))
                .map(CustomerResponse::from)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerProfileResponse getProfile(Long customerId) {
        return CustomerProfileResponse.from(requireCustomer(customerId));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerContactResponse getContact(Long customerId) {
        Customer customer = requireCustomer(customerId);
        // Logged masked: this method is the one path that decrypts contact data, so it is also the
        // one worth being able to audit afterwards.
        log.debug("Released contact details for customer {} ({})", customerId,
                org.retailbank360.common.util.MaskingUtil.maskEmail(customer.getEmail()));
        return CustomerContactResponse.from(customer);
    }

    /** Copies only the fields a caller is allowed to set. */
    private void applyRequest(Customer customer, CustomerRequest request) {
        customer.setFirstName(request.getFirstName());
        customer.setMiddleName(request.getMiddleName());
        customer.setLastName(request.getLastName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setCustomerAddress(request.getCustomerAddress());
        customer.setDateOfBirth(request.getDateOfBirth());
        customer.setPanNumber(request.getPanNumber());
        customer.setAadhaarNumber(request.getAadhaarNumber());
        customer.setAnnualIncome(request.getAnnualIncome());
        customer.setCreditScore(request.getCreditScore());
        customer.setEmploymentType(request.getEmploymentType());
    }

    private void validateAge(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            return;
        }
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < MINIMUM_AGE_YEARS) {
            throw new BusinessRuleViolationException("UNDERAGE_CUSTOMER",
                    "A customer must be at least " + MINIMUM_AGE_YEARS + " years old");
        }
    }

    /**
     * Uniqueness on email and phone, checked through the blind indexes.
     *
     * <p>The database constraints are the real guarantee; this check exists to return a clean 409
     * with a useful message instead of a raw constraint violation. A {@code null} email or phone is
     * skipped, so a partial update that does not touch a contact field is not blocked by it.</p>
     */
    private void assertContactIsFree(String email, String phone, Long allowedCustomerId) {
        if (email != null) {
            customerRepository.findByEmailIndex(CryptoHolder.blindIndex(email))
                    .filter(existing -> !existing.getId().equals(allowedCustomerId))
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException("That email address is already registered");
                    });
        }
        if (phone != null) {
            customerRepository.findByPhoneIndex(CryptoHolder.blindIndex(phone))
                    .filter(existing -> !existing.getId().equals(allowedCustomerId))
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException("That phone number is already registered");
                    });
        }
    }

    private String generateCustomerNumber() {
        String candidate;
        do {
            candidate = "CUST" + IdGenerator.numeric(10);
        } while (customerRepository.existsByCustomerNumber(candidate));
        return candidate;
    }

    private Customer requireCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }
}
