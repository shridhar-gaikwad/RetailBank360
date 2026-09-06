package org.retailbank360.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.config.SecurityProperties;
import org.retailbank360.common.constants.Roles;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the JWT contract shared by every service. */
class JwtTokenServiceTest {

    private static final String TEST_SECRET = "test-only-retailbank360-signing-secret-32-bytes+";

    private SecurityProperties properties;
    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        properties = testProperties();
        tokenService = new JwtTokenService(properties);
    }

    /**
     * The signing key is stated explicitly, because {@code SecurityProperties} ships without one:
     * a usable key comes from the environment or the local profile, never from the repository.
     */
    private static SecurityProperties testProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSecret(TEST_SECRET);
        return properties;
    }

    @Test
    @DisplayName("An access token round-trips into the principal the services authorise against")
    void issuesAndVerifiesAnAccessToken() {
        String token = tokenService.generateAccessToken(7L, "customer1", Roles.CUSTOMER, 42L);

        Optional<AuthenticatedUser> user = tokenService.authenticate(token);

        assertThat(user).isPresent();
        assertThat(user.get().userId()).isEqualTo(7L);
        assertThat(user.get().username()).isEqualTo("customer1");
        assertThat(user.get().role()).isEqualTo(Roles.CUSTOMER);
        assertThat(user.get().customerId()).isEqualTo(42L);
        assertThat(user.get().isCustomer()).isTrue();
    }

    @Test
    @DisplayName("A service token carries the SERVICE role and no customer")
    void issuesAServiceToken() {
        Optional<AuthenticatedUser> user =
                tokenService.authenticate(tokenService.generateServiceToken("account-service"));

        assertThat(user).isPresent();
        assertThat(user.get().role()).isEqualTo(Roles.SERVICE);
        assertThat(user.get().isService()).isTrue();
        assertThat(user.get().customerId()).isNull();
    }

    @Test
    @DisplayName("A refresh token is not accepted as a credential for a resource endpoint")
    void rejectsARefreshTokenOnResourceEndpoints() {
        String refreshToken = tokenService.generateRefreshToken(7L, "customer1", Roles.CUSTOMER, 42L);

        // It parses fine - the auth service needs to read it - but it grants no access.
        assertThat(tokenService.parse(refreshToken)).isNotNull();
        assertThat(tokenService.authenticate(refreshToken)).isEmpty();
    }

    @Test
    @DisplayName("A half-finished MFA challenge grants no access")
    void rejectsAnMfaChallengeToken() {
        assertThat(tokenService.authenticate(tokenService.generateMfaChallengeToken(7L, "customer1")))
                .isEmpty();
    }

    @Test
    @DisplayName("A token signed with another key is rejected")
    void rejectsAForeignSignature() {
        SecurityProperties otherProperties = testProperties();
        otherProperties.getJwt().setSecret("a-completely-different-signing-secret-32-bytes+");
        String foreignToken = new JwtTokenService(otherProperties)
                .generateAccessToken(1L, "attacker", Roles.ADMIN, null);

        assertThat(tokenService.authenticate(foreignToken)).isEmpty();
        assertThatThrownBy(() -> tokenService.parse(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("An expired token is rejected once it is past the clock-skew allowance")
    void rejectsAnExpiredToken() {
        properties.getJwt().setAccessTokenTtl(Duration.ofSeconds(-120));
        properties.getJwt().setClockSkew(Duration.ofSeconds(1));

        assertThat(tokenService.authenticate(
                tokenService.generateAccessToken(1L, "customer1", Roles.CUSTOMER, 1L))).isEmpty();
    }

    @Test
    @DisplayName("Garbage is rejected rather than throwing out of the filter")
    void rejectsMalformedInput() {
        assertThat(tokenService.authenticate("not-a-token")).isEmpty();
        assertThat(tokenService.authenticate("")).isEmpty();
        assertThat(tokenService.secondsUntilExpiry("not-a-token")).isZero();
    }

    @Test
    @DisplayName("A signing secret shorter than 32 bytes is refused at startup")
    void refusesAWeakSigningKey() {
        SecurityProperties weak = testProperties();
        weak.getJwt().setSecret("too-short");

        assertThatThrownBy(() -> new JwtTokenService(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HS256 requires at least 32");
    }

    @Test
    @DisplayName("A missing signing key stops startup and names the variable to set")
    void refusesAMissingSigningKey() {
        assertThatThrownBy(() -> new JwtTokenService(new SecurityProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETAILBANK_JWT_SECRET");
    }
}
