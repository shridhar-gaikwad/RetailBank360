package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.constants.CustomerStatus;
import org.retailbank360.constants.KycStatus;
import org.retailbank360.dto.CustomerResponse;
import org.retailbank360.entity.Customer;
import org.retailbank360.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Transactional half of a KYC decision.
 *
 * <p>Deliberately a separate bean from {@link CustomerServiceImpl}. The lock has to be taken
 * <em>outside</em> the transaction, so the code that takes it cannot be the same proxied method that
 * carries {@code @Transactional} - a self-invocation would bypass the proxy and silently run without
 * a transaction, which is exactly the kind of bug that makes a {@code PESSIMISTIC_WRITE} read do
 * nothing at all.</p>
 */
@Slf4j
@Service
public class KycTransactionService {

    private final CustomerRepository customerRepository;
    private final AuditPublisher auditPublisher;

    public KycTransactionService(CustomerRepository customerRepository, AuditPublisher auditPublisher) {
        this.customerRepository = customerRepository;
        this.auditPublisher = auditPublisher;
    }

    /** Re-reads the customer under a row lock, applies the decision and commits. */
    @Transactional
    public CustomerResponse applyDecision(Long customerId, KycStatus decision, String remarks) {
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));

        if (customer.getStatus() == CustomerStatus.CLOSED) {
            throw new BusinessRuleViolationException("Customer " + customerId + " is closed");
        }
        if (customer.getKycStatus() == decision) {
            log.debug("Customer {} is already {}; nothing to do", customerId, decision);
            return CustomerResponse.from(customer);
        }
        if (decision == KycStatus.VERIFIED) {
            requireKycDocuments(customer);
        }

        customer.setKycStatus(decision);
        customer.setKycRemarks(remarks);
        customer.setKycVerifiedBy(SecurityUtils.currentUsername());
        customer.setKycVerifiedAt(LocalDateTime.now());

        Customer saved = customerRepository.save(customer);
        log.info("KYC for customer {} set to {} by {}", customerId, decision, saved.getKycVerifiedBy());
        auditPublisher.publishSuccess(
                decision == KycStatus.VERIFIED ? AuditActions.KYC_VERIFIED : AuditActions.KYC_REJECTED,
                "CUSTOMER", customerId, null);
        return CustomerResponse.from(saved);
    }

    /** KYC can only be approved once identity documents are actually on file. */
    private void requireKycDocuments(Customer customer) {
        if (customer.getPanNumber() == null || customer.getPanNumber().isBlank()) {
            throw new BusinessRuleViolationException("KYC_DOCUMENTS_MISSING",
                    "PAN is required before KYC can be verified");
        }
        if (customer.getDateOfBirth() == null) {
            throw new BusinessRuleViolationException("KYC_DOCUMENTS_MISSING",
                    "Date of birth is required before KYC can be verified");
        }
    }
}
