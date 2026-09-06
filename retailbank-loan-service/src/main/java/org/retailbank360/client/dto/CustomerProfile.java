package org.retailbank360.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Eligibility inputs read from customer-service. Tolerant reader: unknown fields are ignored. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerProfile {

    private Long customerId;

    private String fullName;

    private String kycStatus;

    private boolean kycVerified;

    private String status;

    private boolean active;

    private BigDecimal annualIncome;

    private Integer creditScore;

    private String employmentType;

    private LocalDate dateOfBirth;
}
