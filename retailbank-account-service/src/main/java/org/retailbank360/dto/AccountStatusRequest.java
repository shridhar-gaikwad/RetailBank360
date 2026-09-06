package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.retailbank360.constants.AccountStatus;

/** Staff change to the status of an account (freeze, reactivate, close). */
@Data
public class AccountStatusRequest {

    @NotNull(message = "Status must not be null")
    private AccountStatus status;

    @NotBlank(message = "A reason is required for a status change")
    @Size(max = 255, message = "Reason must not exceed 255 characters")
    private String reason;
}
