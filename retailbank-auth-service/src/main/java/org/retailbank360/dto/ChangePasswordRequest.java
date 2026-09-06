package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Self-service password change. Revokes every existing session on success. */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password must not be blank")
    private String currentPassword;

    @NotBlank(message = "New password must not be blank")
    @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).+$",
            message = "Password must contain at least one letter and one digit")
    private String newPassword;
}
