package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.dto.ChangePasswordRequest;
import org.retailbank360.dto.LoginRequest;
import org.retailbank360.dto.LoginResponse;
import org.retailbank360.dto.MfaSetupResponse;
import org.retailbank360.dto.MfaVerifyRequest;
import org.retailbank360.dto.RefreshTokenRequest;
import org.retailbank360.dto.RegisterRequest;
import org.retailbank360.dto.UserResponse;
import org.retailbank360.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Authentication endpoints.
 *
 * <p>{@code /login}, {@code /mfa/verify} and {@code /refresh} are the only public paths - see
 * {@code retailbank.security.public-paths} in {@code application.yml}. Login and refresh are also
 * rate limited by the shared filter, which is the brute-force guard the requirement asks for.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Password login. Answers with tokens, or with an MFA challenge when the account requires one. */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** Second factor: challenge token plus the current authenticator code. */
    @PostMapping("/mfa/verify")
    public ResponseEntity<LoginResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        return ResponseEntity.ok(authService.verifyMfa(request));
    }

    /** Exchanges a refresh token for a new pair; the presented token is rotated out. */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /** Revokes the supplied refresh token. Idempotent. */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        authService.logout(request == null ? null : request.getRefreshToken());
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    /** Creates a login. Administrators only, since it assigns a role. */
    @PostMapping("/register")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /** Profile of the caller, resolved from the bearer token. */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(authService.currentUser(SecurityUtils.requireCurrentUser().username()));
    }

    @PostMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(SecurityUtils.requireCurrentUser().username(), request);
        return ResponseEntity.ok(Map.of("message", "Password changed. All other sessions were revoked."));
    }

    /** Enrols the caller in TOTP. The secret is returned exactly once. */
    @PostMapping("/mfa/enable")
    public ResponseEntity<MfaSetupResponse> enableMfa() {
        return ResponseEntity.ok(authService.enableMfa(SecurityUtils.requireCurrentUser().username()));
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Map<String, String>> disableMfa() {
        authService.disableMfa(SecurityUtils.requireCurrentUser().username());
        return ResponseEntity.ok(Map.of("message", "Multi-factor authentication disabled"));
    }

    @GetMapping("/users")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<List<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(authService.listUsers(page, size));
    }

    @PostMapping("/users/{id}/enable")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<UserResponse> enable(@PathVariable Long id) {
        return ResponseEntity.ok(authService.setEnabled(id, true));
    }

    @PostMapping("/users/{id}/disable")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<UserResponse> disable(@PathVariable Long id) {
        return ResponseEntity.ok(authService.setEnabled(id, false));
    }

    /** Clears a failed-attempt lockout without touching the password. */
    @PostMapping("/users/{id}/unlock")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<UserResponse> unlock(@PathVariable Long id) {
        return ResponseEntity.ok(authService.unlock(id));
    }

    @PostMapping("/users/{id}/revoke-sessions")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, String>> revokeSessions(@PathVariable Long id) {
        authService.revokeAllSessions(id, "revoked by administrator");
        return ResponseEntity.ok(Map.of("message", "All sessions revoked for user " + id));
    }
}
