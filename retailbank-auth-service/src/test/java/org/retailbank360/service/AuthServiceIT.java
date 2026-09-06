package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.DuplicateResourceException;
import org.retailbank360.common.exception.UnauthorizedOperationException;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.dto.ChangePasswordRequest;
import org.retailbank360.dto.LoginRequest;
import org.retailbank360.dto.LoginResponse;
import org.retailbank360.dto.MfaSetupResponse;
import org.retailbank360.dto.MfaVerifyRequest;
import org.retailbank360.dto.RefreshTokenRequest;
import org.retailbank360.dto.RegisterRequest;
import org.retailbank360.entity.UserAccount;
import org.retailbank360.repository.RefreshTokenRepository;
import org.retailbank360.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Login, lockout, multi-factor authentication and refresh-token rotation. */
@SpringBootTest
class AuthServiceIT {

    @Autowired
    private AuthService authService;

    @Autowired
    private TotpService totpService;

    @Autowired
    private JwtTokenService tokenService;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("A registered user can log in and the token carries their role")
    void registersAndLogsIn() {
        authService.register(register("teller9", "Teller@123", Roles.TELLER, null));

        LoginResponse login = authService.login(credentials("teller9", "Teller@123"));

        assertThat(login.isMfaRequired()).isFalse();
        assertThat(login.getAccessToken()).isNotBlank();
        assertThat(login.getRefreshToken()).isNotBlank();
        assertThat(login.getRole()).isEqualTo(Roles.TELLER);
        assertThat(tokenService.authenticate(login.getAccessToken()))
                .get()
                .satisfies(user -> assertThat(user.role()).isEqualTo(Roles.TELLER));
    }

    @Test
    @DisplayName("The stored password is a hash, never the plaintext")
    void storesOnlyAPasswordHash() {
        authService.register(register("hashed", "Secret@123", Roles.TELLER, null));

        UserAccount stored = userRepository.findByUsername("hashed").orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo("Secret@123").startsWith("$2");
    }

    @Test
    @DisplayName("A wrong username and a wrong password give the same answer")
    void doesNotLeakWhetherAnAccountExists() {
        authService.register(register("known", "Right@123", Roles.TELLER, null));

        // Identical message either way, so the endpoint cannot be used to enumerate accounts.
        assertThatThrownBy(() -> authService.login(credentials("known", "Wrong@123")))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessage("Invalid username or password");
        assertThatThrownBy(() -> authService.login(credentials("nobody", "Wrong@123")))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    @DisplayName("Repeated failures lock the account, and the right password still will not open it")
    void locksTheAccountAfterRepeatedFailures() {
        authService.register(register("bruteforced", "Right@123", Roles.TELLER, null));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authService.login(credentials("bruteforced", "Wrong@123")))
                    .isInstanceOf(UnauthorizedOperationException.class);
        }

        assertThat(userRepository.findByUsername("bruteforced").orElseThrow().isLockedOut()).isTrue();
        assertThatThrownBy(() -> authService.login(credentials("bruteforced", "Right@123")))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessageContaining("temporarily locked");
    }

    @Test
    @DisplayName("An administrator can clear a lockout")
    void administratorCanUnlockAnAccount() {
        var user = authService.register(register("lockedout", "Right@123", Roles.TELLER, null));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authService.login(credentials("lockedout", "Wrong@123")))
                    .isInstanceOf(UnauthorizedOperationException.class);
        }

        authService.unlock(user.getId());

        assertThat(authService.login(credentials("lockedout", "Right@123")).getAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("With MFA on, the password alone yields only a challenge")
    void requiresTheSecondFactor() {
        authService.register(register("mfauser", "Secret@123", Roles.CUSTOMER, 5L));
        MfaSetupResponse setup = authService.enableMfa("mfauser");

        LoginResponse challenge = authService.login(credentials("mfauser", "Secret@123"));

        assertThat(challenge.isMfaRequired()).isTrue();
        assertThat(challenge.getAccessToken()).isNull();
        assertThat(challenge.getMfaToken()).isNotBlank();
        // The challenge token is not a credential: it opens no resource endpoint.
        assertThat(tokenService.authenticate(challenge.getMfaToken())).isEmpty();

        MfaVerifyRequest verify = new MfaVerifyRequest();
        verify.setMfaToken(challenge.getMfaToken());
        verify.setCode(totpService.currentCode(setup.getSecret()));

        LoginResponse completed = authService.verifyMfa(verify);
        assertThat(completed.getAccessToken()).isNotBlank();
        assertThat(completed.getCustomerId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("A wrong MFA code is refused")
    void refusesAWrongMfaCode() {
        authService.register(register("mfauser2", "Secret@123", Roles.CUSTOMER, 6L));
        authService.enableMfa("mfauser2");

        LoginResponse challenge = authService.login(credentials("mfauser2", "Secret@123"));
        MfaVerifyRequest verify = new MfaVerifyRequest();
        verify.setMfaToken(challenge.getMfaToken());
        verify.setCode("000000");

        assertThatThrownBy(() -> authService.verifyMfa(verify))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessageContaining("Invalid multi-factor");
    }

    @Test
    @DisplayName("Refreshing rotates the token, so the old one stops working")
    void rotatesRefreshTokens() {
        authService.register(register("refresher", "Secret@123", Roles.TELLER, null));
        LoginResponse login = authService.login(credentials("refresher", "Secret@123"));

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(login.getRefreshToken());
        LoginResponse refreshed = authService.refresh(request);

        assertThat(refreshed.getAccessToken()).isNotBlank();
        assertThat(refreshed.getRefreshToken()).isNotEqualTo(login.getRefreshToken());

        // A stolen refresh token is usable once at most, because the original is retired on use.
        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("Logging out revokes the session")
    void logoutRevokesTheSession() {
        authService.register(register("logsout", "Secret@123", Roles.TELLER, null));
        LoginResponse login = authService.login(credentials("logsout", "Secret@123"));

        authService.logout(login.getRefreshToken());

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(login.getRefreshToken());
        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedOperationException.class);
    }

    @Test
    @DisplayName("Changing a password revokes every existing session")
    void passwordChangeRevokesAllSessions() {
        authService.register(register("changer", "Secret@123", Roles.TELLER, null));
        LoginResponse login = authService.login(credentials("changer", "Secret@123"));

        ChangePasswordRequest change = new ChangePasswordRequest();
        change.setCurrentPassword("Secret@123");
        change.setNewPassword("Newer@1234");
        authService.changePassword("changer", change);

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(login.getRefreshToken());
        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedOperationException.class);

        assertThat(authService.login(credentials("changer", "Newer@1234")).getAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("Role and customer-id rules are enforced when a login is created")
    void validatesRoleAndCustomerLinkage() {
        assertThatThrownBy(() -> authService.register(register("bad", "Secret@123", "SUPERUSER", null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Role must be one of");

        assertThatThrownBy(() -> authService.register(register("bad", "Secret@123", Roles.CUSTOMER, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must be linked to a customer id");

        assertThatThrownBy(() -> authService.register(register("bad", "Secret@123", Roles.ADMIN, 3L)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only a CUSTOMER login");
    }

    @Test
    @DisplayName("A username cannot be taken twice")
    void refusesADuplicateUsername() {
        authService.register(register("taken", "Secret@123", Roles.TELLER, null));

        assertThatThrownBy(() -> authService.register(register("taken", "Other@123", Roles.TELLER, null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("A disabled account cannot log in")
    void refusesADisabledAccount() {
        var user = authService.register(register("disabled", "Secret@123", Roles.TELLER, null));
        authService.setEnabled(user.getId(), false);

        assertThatThrownBy(() -> authService.login(credentials("disabled", "Secret@123")))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessageContaining("disabled");
    }

    private RegisterRequest register(String username, String password, String role, Long customerId) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setRole(role);
        request.setCustomerId(customerId);
        request.setEmail(username + "@retailbank360.local");
        return request;
    }

    private LoginRequest credentials(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
