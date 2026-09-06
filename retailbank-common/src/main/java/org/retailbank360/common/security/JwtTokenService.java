package org.retailbank360.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.SecurityProperties;
import org.retailbank360.common.constants.Roles;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies the HS256 JSON Web Tokens shared by every RetailBank360 service.
 *
 * <p>The auth-service mints access and refresh tokens; resource services only verify them. The same
 * class also mints the short lived {@code SERVICE} token that one microservice presents when calling
 * another, so internal endpoints are never anonymous.</p>
 */
@Slf4j
public class JwtTokenService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_CUSTOMER_ID = "cid";
    public static final String CLAIM_TOKEN_TYPE = "typ";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String TYPE_SERVICE = "service";
    /** Half-authenticated token issued between a correct password and a correct MFA code. */
    public static final String TYPE_MFA_CHALLENGE = "mfa";

    private final SecurityProperties properties;
    private final SecretKey signingKey;

    public JwtTokenService(SecurityProperties properties) {
        this.properties = properties;
        String configured = properties.getJwt().getSecret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "No JWT signing key is configured. Set the RETAILBANK_JWT_SECRET environment "
                            + "variable (at least 32 characters), or run with the local 'h2' profile "
                            + "which supplies a throwaway key. The same key must be used by every "
                            + "service, or tokens issued by auth-service will not verify.");
        }
        byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "RETAILBANK_JWT_SECRET is only " + secret.length + " bytes; HS256 requires at "
                            + "least 32.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    public String generateAccessToken(Long userId, String username, String role, Long customerId) {
        return build(username, role, userId, customerId, TYPE_ACCESS, properties.getJwt().getAccessTokenTtl().toMillis());
    }

    public String generateRefreshToken(Long userId, String username, String role, Long customerId) {
        return build(username, role, userId, customerId, TYPE_REFRESH, properties.getJwt().getRefreshTokenTtl().toMillis());
    }

    /** Machine-to-machine token used by Feign interceptors for internal endpoints. */
    public String generateServiceToken(String serviceName) {
        return build("service:" + serviceName, Roles.SERVICE, null, null, TYPE_SERVICE,
                properties.getJwt().getServiceTokenTtl().toMillis());
    }

    private String build(String subject, String role, Long userId, Long customerId, String type, long ttlMillis) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                // A unique jti makes every issuance distinct. Without it two tokens minted for the
                // same user inside the same second are byte-identical, which collides with the
                // uniqueness constraint on the stored refresh-token hash - and would make one
                // logout revoke a session the user had not asked to end.
                .id(java.util.UUID.randomUUID().toString())
                .subject(subject)
                .issuer(properties.getJwt().getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TOKEN_TYPE, type);
        if (userId != null) {
            builder.claim(CLAIM_USER_ID, userId);
        }
        if (customerId != null) {
            builder.claim(CLAIM_CUSTOMER_ID, customerId);
        }
        return builder.signWith(signingKey).compact();
    }

    /** Verifies signature, issuer and expiry. Throws {@link JwtException} when the token is not usable. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getJwt().getIssuer())
                .clockSkewSeconds(properties.getJwt().getClockSkew().toSeconds())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Short lived token proving the password step passed but MFA has not been completed yet. It
     * grants no access on its own: {@link #authenticate} refuses it.
     */
    public String generateMfaChallengeToken(Long userId, String username) {
        return build(username, null, userId, null, TYPE_MFA_CHALLENGE, Duration.ofMinutes(5).toMillis());
    }

    /** Verifies a token and maps it onto a principal, or returns empty when it cannot be trusted. */
    public Optional<AuthenticatedUser> authenticate(String token) {
        try {
            Claims claims = parse(token);
            String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
            // Only a full access token or an internal service token grants access. Refresh tokens and
            // half-finished MFA challenges are explicitly not credentials for a resource endpoint.
            if (!TYPE_ACCESS.equals(type) && !TYPE_SERVICE.equals(type)) {
                log.debug("Token of type '{}' presented on a resource endpoint; rejecting", type);
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(
                    claims.get(CLAIM_USER_ID, Integer.class) == null
                            ? null : claims.get(CLAIM_USER_ID, Integer.class).longValue(),
                    claims.getSubject(),
                    claims.get(CLAIM_ROLE, String.class),
                    claims.get(CLAIM_CUSTOMER_ID, Integer.class) == null
                            ? null : claims.get(CLAIM_CUSTOMER_ID, Integer.class).longValue()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Seconds until the supplied token expires; 0 when it is already expired or unreadable. */
    public long secondsUntilExpiry(String token) {
        try {
            long millis = parse(token).getExpiration().getTime() - System.currentTimeMillis();
            return Math.max(0, millis / 1000);
        } catch (JwtException | IllegalArgumentException e) {
            return 0;
        }
    }
}
