package org.retailbank360.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.retailbank360.entity.Customer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Minimal profile consumed by other microservices.
 *
 * <p>account-service reads it to enforce the KYC gate at account opening, and loan-service reads it
 * to score eligibility. It carries no contact details at all, so an internal call cannot be turned
 * into a way of harvesting PII.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfileResponse {

    private Long customerId;

    private String customerNumber;

    private String fullName;

    private String kycStatus;

    private boolean kycVerified;

    private String status;

    private boolean active;

    private BigDecimal annualIncome;

    private Integer creditScore;

    private String employmentType;

    private LocalDate dateOfBirth;

    public static CustomerProfileResponse from(Customer customer) {
        return CustomerProfileResponse.builder()
                .customerId(customer.getId())
                .customerNumber(customer.getCustomerNumber())
                .fullName(customer.getFullName())
                .kycStatus(customer.getKycStatus().name())
                .kycVerified(customer.isKycVerified())
                .status(customer.getStatus().name())
                .active(customer.getStatus() == org.retailbank360.constants.CustomerStatus.ACTIVE)
                .annualIncome(customer.getAnnualIncome())
                .creditScore(customer.getCreditScore())
                .employmentType(customer.getEmploymentType() == null ? null : customer.getEmploymentType().name())
                .dateOfBirth(customer.getDateOfBirth())
                .build();
    }
}
