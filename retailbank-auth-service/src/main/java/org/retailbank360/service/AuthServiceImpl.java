package org.retailbank360.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.config.SecurityProperties;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.DuplicateResourceException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.exception.UnauthorizedOperationException;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.common.web.PageRequests;
import org.retailbank360.dto.ChangePasswordRequest;
import org.retailbank360.dto.LoginRequest;
import org.retailbank360.dto.LoginResponse;
import org.retailbank360.dto.MfaSetupResponse;
import org.retailbank360.dto.MfaVerifyRequest;
import org.retailbank360.dto.RefreshTokenRequest;
import org.retailbank360.dto.RegisterRequest;
import org.retailbank360.dto.UserResponse;
import org.retailbank360.entity.RefreshTokenRecord;
import org.retailbank360.entity.UserAccount;
import org.retailbank360.repository.RefreshTokenRepository;
import org.retailbank360.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Login, MFA, refresh and credential management.
 *
 * <p>Security decisions worth calling out:</p>
 * <ul>
 *   <li>A wrong username and a wrong password produce the <em>same</em> message and the same work,
 *       so the endpoint cannot be used to enumerate accounts.</li>
 *   <li>Failed attempts are counted by {@link LoginAttemptService} in its own transaction, under a
 *       row lock. Both halves matter: the separate transaction survives the rollback caused by the
 *       rejection itself, and the row lock stops parallel guesses from all reading the same stale
 *       count. Without either, the lockout never fires.</li>
 *   <li>Refresh tokens are stored hashed and can be revoked; changing a password revokes every live
 *       session.</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final Set<String> ASSIGNABLE_ROLES =
            Set.of(Roles.CUSTOMER, Roles.TELLER, Roles.LOAN_OFFICER, Roles.ADMIN);

    /** Deliberately identical for an unknown user and a wrong password. */
    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private final UserAccountRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final TotpService totpService;
    private final LoginAttemptService loginAttemptService;
    private final SecurityProperties securityProperties;
    private final AuditPublisher auditPublisher;

    public AuthServiceImpl(UserAccountRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService tokenService,
                           TotpService totpService,
                           LoginAttemptService loginAttemptService,
                           SecurityProperties securityProperties,
                           AuditPublisher auditPublisher) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.totpService = totpService;
        this.loginAttemptService = loginAttemptService;
        this.securityProperties = securityProperties;
        this.auditPublisher = auditPublisher;
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String role = request.getRole() == null ? "" : request.getRole().trim().toUpperCase();
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new BusinessRuleViolationException(
                    "Role must be one of " + ASSIGNABLE_ROLES + ", received: " + request.getRole());
        }
        if (Roles.CUSTOMER.equals(role) && request.getCustomerId() == null) {
            throw new BusinessRuleViolationException("A CUSTOMER login must be linked to a customer id");
        }
        if (!Roles.CUSTOMER.equals(role) && request.getCustomerId() != null) {
            throw new BusinessRuleViolationException("Only a CUSTOMER login may carry a customer id");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already taken: " + request.getUsername());
        }

        UserAccount user = new UserAccount();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setCustomerId(request.getCustomerId());
        user.setEmail(request.getEmail());
        user.setEnabled(true);

        if (request.isEnableMfa()) {
            user.setMfaEnabled(true);
            user.setMfaSecret(totpService.generateSecret());
        }

        UserAccount saved = userRepository.save(user);
        log.info("Created {} login '{}' (id {})", saved.getRole(), saved.getUsername(), saved.getId());
        auditPublisher.publishSuccess(AuditActions.USER_REGISTERED, "USER", saved.getId(), null);
        return UserResponse.from(saved);
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        UserAccount user = userRepository.findByUsername(request.getUsername()).orElse(null);

        if (user == null) {
            // Spend roughly the same time as a real check so timing does not reveal existence.
            passwordEncoder.matches(request.getPassword(), "$2a$10$ignoredignoredignoredignoredignoredignoredignoredigno");
            auditPublisher.publishFailure(AuditActions.LOGIN_FAILED, "USER", request.getUsername(), null,
                    "Unknown username");
            throw new UnauthorizedOperationException(INVALID_CREDENTIALS);
        }

        if (!user.isEnabled()) {
            auditPublisher.publishFailure(AuditActions.LOGIN_FAILED, "USER", user.getId(), null,
                    "Account disabled");
            throw new UnauthorizedOperationException("This account is disabled. Contact your administrator.");
        }

        if (user.isLockedOut()) {
            long seconds = Math.max(1, user.getLockedUntil().getEpochSecond() - Instant.now().getEpochSecond());
            auditPublisher.publishFailure(AuditActions.LOGIN_FAILED, "USER", user.getId(), null,
                    "Attempted login during lockout");
            throw new UnauthorizedOperationException(
                    "Account temporarily locked after too many failed attempts. Try again in " + seconds + "s.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            loginAttemptService.registerFailure(user.getId());
            throw new UnauthorizedOperationException(INVALID_CREDENTIALS);
        }

        if (user.isMfaEnabled()) {
            // A code supplied up front completes both factors in one call; otherwise challenge.
            if (request.getMfaCode() == null || request.getMfaCode().isBlank()) {
                auditPublisher.publishSuccess(AuditActions.MFA_CHALLENGED, "USER", user.getId(), null);
                return LoginResponse.mfaChallenge(
                        tokenService.generateMfaChallengeToken(user.getId(), user.getUsername()),
                        user.getUsername());
            }
            if (!totpService.verify(user.getMfaSecret(), request.getMfaCode())) {
                loginAttemptService.registerFailure(user.getId());
                throw new UnauthorizedOperationException("Invalid multi-factor authentication code");
            }
            auditPublisher.publishSuccess(AuditActions.MFA_VERIFIED, "USER", user.getId(), null);
        }

        return completeLogin(user);
    }

    @Override
    @Transactional
    public LoginResponse verifyMfa(MfaVerifyRequest request) {
        Claims claims;
        try {
            claims = tokenService.parse(request.getMfaToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedOperationException("The MFA challenge is invalid or has expired. Log in again.");
        }
        if (!JwtTokenService.TYPE_MFA_CHALLENGE.equals(claims.get(JwtTokenService.CLAIM_TOKEN_TYPE, String.class))) {
            throw new UnauthorizedOperationException("That token is not an MFA challenge");
        }

        UserAccount user = userRepository.findByUsername(claims.getSubject())
                .orElseThrow(() -> new UnauthorizedOperationException(INVALID_CREDENTIALS));

        if (!user.isMfaEnabled()) {
            throw new BusinessRuleViolationException("Multi-factor authentication is not enabled for this account");
        }
        if (user.isLockedOut()) {
            throw new UnauthorizedOperationException("Account temporarily locked after too many failed attempts");
        }
        if (!totpService.verify(user.getMfaSecret(), request.getCode())) {
            loginAttemptService.registerFailure(user.getId());
            throw new UnauthorizedOperationException("Invalid multi-factor authentication code");
        }

        auditPublisher.publishSuccess(AuditActions.MFA_VERIFIED, "USER", user.getId(), null);
        return completeLogin(user);
    }

    @Override
    @Transactional
    public LoginResponse refresh(RefreshTokenRequest request) {
        Claims claims;
        try {
            claims = tokenService.parse(request.getRefreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedOperationException("The refresh token is invalid or has expired");
        }
        if (!JwtTokenService.TYPE_REFRESH.equals(claims.get(JwtTokenService.CLAIM_TOKEN_TYPE, String.class))) {
            throw new UnauthorizedOperationException("That token is not a refresh token");
        }

        RefreshTokenRecord stored = refreshTokenRepository.findByTokenHash(hash(request.getRefreshToken()))
                .orElseThrow(() -> new UnauthorizedOperationException(
                        "This refresh token is not recognised. Log in again."));

        if (!stored.isUsable()) {
            throw new UnauthorizedOperationException("This session has been revoked. Log in again.");
        }

        UserAccount user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedOperationException(INVALID_CREDENTIALS));
        if (!user.isEnabled()) {
            throw new UnauthorizedOperationException("This account is disabled");
        }

        // Rotate: the presented token is retired as the new pair is issued, so a stolen refresh token
        // has a single-use window rather than an unbounded one.
        revoke(stored, "rotated on refresh");
        auditPublisher.publishSuccess(AuditActions.TOKEN_REFRESHED, "USER", user.getId(), null);
        return issueTokens(user);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(refreshToken)).ifPresent(record -> {
            revoke(record, "logout");
            auditPublisher.publishSuccess(AuditActions.LOGOUT, "USER", record.getUserId(), null);
        });
    }

    @Override
    @Transactional
    public void revokeAllSessions(Long userId, String reason) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now(), reason);
        log.info("Revoked {} session(s) for user {} ({})", revoked, userId, reason);
    }

    @Override
    @Transactional
    public MfaSetupResponse enableMfa(String username) {
        UserAccount user = requireUser(username);
        String secret = totpService.generateSecret();
        user.setMfaSecret(secret);
        user.setMfaEnabled(true);
        userRepository.save(user);

        auditPublisher.publishSuccess(AuditActions.MFA_ENABLED, "USER", user.getId(), null);
        return MfaSetupResponse.builder()
                .username(username)
                .secret(secret)
                .otpAuthUri(totpService.buildOtpAuthUri(securityProperties.getJwt().getIssuer(), username, secret))
                .message("Scan this in an authenticator app now. The secret is not shown again.")
                .build();
    }

    @Override
    @Transactional
    public void disableMfa(String username) {
        UserAccount user = requireUser(username);
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        userRepository.save(user);
        log.info("Disabled MFA for '{}'", username);
    }

    @Override
    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        UserAccount user = requireUser(username);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new UnauthorizedOperationException("The current password is incorrect");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessRuleViolationException("The new password must differ from the current one");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Anyone holding a session established with the old password loses it.
        revokeAllSessions(user.getId(), "password changed");
        log.info("Password changed for '{}'", username);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse currentUser(String username) {
        return UserResponse.from(requireUser(username));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers(int page, int size) {
        return userRepository.findAll(PageRequests.of(page, size))
                .map(UserResponse::from)
                .getContent();
    }

    @Override
    @Transactional
    public UserResponse setEnabled(Long userId, boolean enabled) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setEnabled(enabled);
        if (!enabled) {
            revokeAllSessions(userId, "account disabled by administrator");
        }
        return UserResponse.from(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse unlock(Long userId) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        log.info("Administrator cleared the lockout on user {}", userId);
        return UserResponse.from(userRepository.save(user));
    }

    private LoginResponse completeLogin(UserAccount user) {
        loginAttemptService.registerSuccess(user.getId());

        log.info("Login succeeded for '{}' ({})", user.getUsername(), user.getRole());
        auditPublisher.publishSuccess(AuditActions.LOGIN_SUCCEEDED, "USER", user.getId(), null);
        return issueTokens(user);
    }

    private LoginResponse issueTokens(UserAccount user) {
        String accessToken = tokenService.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole(), user.getCustomerId());
        String refreshToken = tokenService.generateRefreshToken(
                user.getId(), user.getUsername(), user.getRole(), user.getCustomerId());

        RefreshTokenRecord record = new RefreshTokenRecord();
        record.setUserId(user.getId());
        record.setTokenHash(hash(refreshToken));
        record.setIssuedAt(Instant.now());
        record.setExpiresAt(Instant.now().plus(securityProperties.getJwt().getRefreshTokenTtl()));
        refreshTokenRepository.save(record);

        return LoginResponse.builder()
                .mfaRequired(false)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(securityProperties.getJwt().getAccessTokenTtl().toSeconds())
                .username(user.getUsername())
                .role(user.getRole())
                .userId(user.getId())
                .customerId(user.getCustomerId())
                .build();
    }

    private void revoke(RefreshTokenRecord record, String reason) {
        record.setRevoked(true);
        record.setRevokedAt(Instant.now());
        record.setRevokedReason(reason);
        refreshTokenRepository.save(record);
    }

    private UserAccount requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    /** Refresh tokens are stored as a SHA-256 digest, never in the clear. */
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
