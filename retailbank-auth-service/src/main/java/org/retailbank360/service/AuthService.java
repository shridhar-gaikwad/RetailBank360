package org.retailbank360.service;

import org.retailbank360.dto.ChangePasswordRequest;
import org.retailbank360.dto.LoginRequest;
import org.retailbank360.dto.LoginResponse;
import org.retailbank360.dto.MfaSetupResponse;
import org.retailbank360.dto.MfaVerifyRequest;
import org.retailbank360.dto.RefreshTokenRequest;
import org.retailbank360.dto.RegisterRequest;
import org.retailbank360.dto.UserResponse;

import java.util.List;

/** Authentication, session and credential management. */
public interface AuthService {

    /** Creates a login. Restricted to administrators at the controller layer. */
    UserResponse register(RegisterRequest request);

    /**
     * Verifies username and password, and either issues tokens or returns an MFA challenge.
     * Repeated failures lock the account for a cool-down window.
     */
    LoginResponse login(LoginRequest request);

    /** Completes an MFA login by verifying the TOTP code against the challenge token. */
    LoginResponse verifyMfa(MfaVerifyRequest request);

    /** Exchanges a valid, unrevoked refresh token for a new access token pair. */
    LoginResponse refresh(RefreshTokenRequest request);

    /** Revokes the supplied refresh token so the session can no longer be renewed. */
    void logout(String refreshToken);

    /** Revokes every live session of a user. Used on password change and by administrators. */
    void revokeAllSessions(Long userId, String reason);

    /** Turns on TOTP for the current user and returns the enrolment secret exactly once. */
    MfaSetupResponse enableMfa(String username);

    void disableMfa(String username);

    void changePassword(String username, ChangePasswordRequest request);

    UserResponse currentUser(String username);

    /** Bounded listing of logins. */
    List<UserResponse> listUsers(int page, int size);

    /** Administrative unlock after a lockout, or after an operator investigation. */
    UserResponse setEnabled(Long userId, boolean enabled);

    /** Clears a failed-attempt lockout without changing the password. */
    UserResponse unlock(Long userId);
}
