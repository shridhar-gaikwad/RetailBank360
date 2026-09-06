package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a login attempt.
 *
 * <p>When the account has MFA switched on, the first call returns {@code mfaRequired=true} and only
 * a short lived challenge token: no access token is issued until the second factor is verified.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {

    private boolean mfaRequired;

    /** Present only while {@code mfaRequired} is true. Cannot be used against resource endpoints. */
    private String mfaToken;

    private String accessToken;

    private String refreshToken;

    private String tokenType;

    /** Access token lifetime in seconds. */
    private Long expiresIn;

    private String username;

    private String role;

    private Long userId;

    private Long customerId;

    public static LoginResponse mfaChallenge(String mfaToken, String username) {
        return LoginResponse.builder()
                .mfaRequired(true)
                .mfaToken(mfaToken)
                .username(username)
                .build();
    }
}
