package org.retailbank360.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Binds {@code retailbank.security.*}. Shared by the auth-service and every resource service. */
@Getter
@Setter
@ConfigurationProperties(prefix = "retailbank.security")
public class SecurityProperties {

    /** Set to false only in slice tests that do not care about authentication. */
    private boolean enabled = true;

    /** Ant patterns that bypass authentication entirely (health, docs, login, ...). */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/h2-console/**"));

    private final Jwt jwt = new Jwt();

    @Getter
    @Setter
    public static class Jwt {

        /**
         * HMAC-SHA signing key. Must be at least 32 bytes and identical on every service.
         *
         * <p>Deliberately empty by default. A usable key ships only in the local {@code h2} profile;
         * every other profile must supply {@code RETAILBANK_JWT_SECRET}, and startup fails loudly if
         * it does not. A signing key committed to a repository is a signing key an attacker has.</p>
         */
        private String secret = "";

        private String issuer = "retailbank360";

        private Duration accessTokenTtl = Duration.ofMinutes(30);

        private Duration refreshTokenTtl = Duration.ofDays(7);

        /** Lifetime of the short lived machine-to-machine token used for internal service calls. */
        private Duration serviceTokenTtl = Duration.ofMinutes(5);

        /** Grace period allowed when validating {@code exp} / {@code nbf}. */
        private Duration clockSkew = Duration.ofSeconds(30);
    }
}
