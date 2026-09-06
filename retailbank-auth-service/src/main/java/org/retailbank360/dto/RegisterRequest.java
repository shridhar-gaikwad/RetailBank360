package org.retailbank360.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Payload for creating a login. Only an administrator may create staff roles. */
@Data
public class RegisterRequest {

    @NotBlank(message = "Username must not be blank")
    @Size(min = 3, max = 60, message = "Username must be between 3 and 60 characters")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$",
            message = "Username may contain only letters, digits, dot, underscore and hyphen")
    private String username;

    @NotBlank(message = "Password must not be blank")
    @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).+$",
            message = "Password must contain at least one letter and one digit")
    private String password;

    @NotBlank(message = "Role must not be blank")
    private String role;

    /** Required for a CUSTOMER login; must be null for staff. */
    private Long customerId;

    @Email(message = "Email must be valid")
    private String email;

    /** Whether to switch TOTP on at creation time. */
    private boolean enableMfa;
}
