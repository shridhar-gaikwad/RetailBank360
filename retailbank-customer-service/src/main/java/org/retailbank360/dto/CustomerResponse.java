package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.constants.CustomerStatus;
import org.retailbank360.constants.EmploymentType;
import org.retailbank360.constants.KycStatus;
import org.retailbank360.entity.Customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Customer as returned by the API.
 *
 * <p>Contact details and identity numbers are masked. Staff see the same masked values; the full
 * plaintext never leaves the service, which is what the requirement means by masking in the UI.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerResponse {

    private Long id;

    private String customerNumber;

    private String fullName;

    private String firstName;

    private String middleName;

    private String lastName;

    private String maskedEmail;

    private String maskedPhone;

    private String maskedPan;

    private String maskedAadhaar;

    /** Only the city / last line is echoed back; the full address stays encrypted at rest. */
    private String addressSummary;

    private LocalDate dateOfBirth;

    private KycStatus kycStatus;

    private LocalDateTime kycVerifiedAt;

    private String kycVerifiedBy;

    private String kycRemarks;

    private BigDecimal annualIncome;

    private Integer creditScore;

    private EmploymentType employmentType;

    private CustomerStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long version;

    public static CustomerResponse from(Customer customer) {
        return CustomerResponse.builder()
                .id(customer.getId())
                .customerNumber(customer.getCustomerNumber())
                .fullName(customer.getFullName())
                .firstName(customer.getFirstName())
                .middleName(customer.getMiddleName())
                .lastName(customer.getLastName())
                .maskedEmail(MaskingUtil.maskEmail(customer.getEmail()))
                .maskedPhone(MaskingUtil.maskPhone(customer.getPhone()))
                .maskedPan(MaskingUtil.maskPan(customer.getPanNumber()))
                .maskedAadhaar(MaskingUtil.maskAadhaar(customer.getAadhaarNumber()))
                .addressSummary(summarise(customer.getCustomerAddress()))
                .dateOfBirth(customer.getDateOfBirth())
                .kycStatus(customer.getKycStatus())
                .kycVerifiedAt(customer.getKycVerifiedAt())
                .kycVerifiedBy(customer.getKycVerifiedBy())
                .kycRemarks(customer.getKycRemarks())
                .annualIncome(customer.getAnnualIncome())
                .creditScore(customer.getCreditScore())
                .employmentType(customer.getEmploymentType())
                .status(customer.getStatus())
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .version(customer.getVersion())
                .build();
    }

    /** Keeps the trailing address component (typically city and postcode) and masks the rest. */
    private static String summarise(String address) {
        if (address == null || address.isBlank()) {
            return address;
        }
        int lastComma = address.lastIndexOf(',');
        return lastComma < 0 ? MaskingUtil.maskAllButLast(address, 6) : "***," + address.substring(lastComma + 1);
    }
}
