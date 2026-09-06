package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Staff change to the risk limits on an account. Every field change is written to change history. */
@Data
public class AccountLimitsRequest {

    @DecimalMin(value = "0.00", message = "Minimum balance must not be negative")
    private BigDecimal minimumBalance;

    private Boolean overdraftAllowed;

    @DecimalMin(value = "0.00", message = "Overdraft limit must not be negative")
    private BigDecimal overdraftLimit;

    @DecimalMin(value = "0.00", message = "Daily transfer limit must not be negative")
    private BigDecimal dailyTransferLimit;

    @NotBlank(message = "A reason is required for a limit change")
    @Size(max = 255, message = "Reason must not exceed 255 characters")
    private String reason;
}
