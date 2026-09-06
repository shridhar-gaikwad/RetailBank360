package org.retailbank360.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Local view of the customer profile served by customer-service.
 *
 * <p>Unknown properties are ignored so customer-service can add fields without breaking this
 * consumer - the usual tolerant-reader rule for service contracts.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerProfile {

    private Long customerId;

    private String customerNumber;

    private String fullName;

    private String kycStatus;

    private boolean kycVerified;

    private String status;

    private boolean active;

    private BigDecimal annualIncome;

    private Integer creditScore;
}
