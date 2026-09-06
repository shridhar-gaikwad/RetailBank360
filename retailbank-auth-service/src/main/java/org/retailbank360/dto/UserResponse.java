package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.entity.UserAccount;

import java.time.LocalDateTime;

/**
 * Login as exposed over the API.
 *
 * <p>Never carries the password hash or the MFA secret, and the email is masked, so an administrator
 * listing users cannot harvest contact details.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {

    private Long id;

    private String username;

    private String role;

    private Long customerId;

    private String maskedEmail;

    private boolean enabled;

    private boolean mfaEnabled;

    private boolean lockedOut;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    public static UserResponse from(UserAccount user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .customerId(user.getCustomerId())
                .maskedEmail(MaskingUtil.maskEmail(user.getEmail()))
                .enabled(user.isEnabled())
                .mfaEnabled(user.isMfaEnabled())
                .lockedOut(user.isLockedOut())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
