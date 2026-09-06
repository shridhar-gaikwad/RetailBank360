package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Teller / administrator decision on a customer KYC file. */
@Data
public class KycDecisionRequest {

    /** Free text kept on the customer record and echoed in the audit trail. */
    @NotBlank(message = "Remarks must not be blank")
    @Size(max = 500, message = "Remarks must not exceed 500 characters")
    private String remarks;
}
