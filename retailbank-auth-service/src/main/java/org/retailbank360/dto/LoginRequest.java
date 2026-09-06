package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Username and password submitted to {@code POST /api/v1/auth/login}. */
@Data
public class LoginRequest {

    @NotBlank(message = "Username must not be blank")
    @Size(max = 60, message = "Username must not exceed 60 characters")
    private String username;

    @NotBlank(message = "Password must not be blank")
    @Size(max = 100, message = "Password must not exceed 100 characters")
    private String password;

    /** Optional TOTP code, so a client can complete both factors in one call. */
    @Size(min = 6, max = 6, message = "The MFA code must be exactly 6 digits")
    private String mfaCode;
}
