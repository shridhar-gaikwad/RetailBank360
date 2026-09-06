package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Second step of an MFA login: the challenge token plus the current authenticator code. */
@Data
public class MfaVerifyRequest {

    @NotBlank(message = "MFA challenge token must not be blank")
    private String mfaToken;

    @NotBlank(message = "MFA code must not be blank")
    @Pattern(regexp = "^[0-9]{6}$", message = "The MFA code must be exactly 6 digits")
    private String code;
}
