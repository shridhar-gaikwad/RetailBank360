package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Loan officer decision on an application. */
@Data
public class LoanDecisionRequest {

    @NotBlank(message = "A reason is required for a loan decision")
    @Size(max = 500, message = "Reason must not exceed 500 characters")
    private String reason;
}
