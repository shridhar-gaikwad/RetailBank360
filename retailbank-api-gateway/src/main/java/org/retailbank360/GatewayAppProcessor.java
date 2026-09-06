package org.retailbank360;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;

/**
 * Single entry point for every RetailBank360 API.
 *
 * <p>Routes are declared in {@code application.yml}; the gateway verifies the bearer token early and
 * rejects an unusable one before it reaches a service. Role checks are deliberately <em>not</em>
 * duplicated here: each service enforces its own authorization, so a call that bypasses the gateway
 * is no less protected.</p>
 *
 * <p>This is a reactive WebFlux application. The shared retailbank-common auto-configurations are
 * guarded on a servlet web application, so they back off cleanly here - only the JWT token service is
 * shared, which is exactly the piece that has to agree across the platform.</p>
 *
 * <p>Runs on {@code http://localhost:8080}.</p>
 */
/*
 * Component scanning is scoped to this service's own packages.
 *
 * Every module in this project uses the org.retailbank360 package, so the default scan would reach
 * into org.retailbank360.common and instantiate the stereotype-annotated classes there
 * unconditionally - ignoring the @ConditionalOn... guards on the auto-configurations that own them.
 * That breaks the services that cannot host them: the reactive gateway has no servlet API, and
 * notification-service has no JPA. Everything the shared module provides arrives through
 * auto-configuration, so the scan stops at its boundary.
 *
 * Entity and repository scanning are unaffected: they key off the package of this class, so the
 * lock and idempotency tables in retailbank-common are still picked up where JPA is present.
 */
/*
 * UserDetailsServiceAutoConfiguration is excluded on purpose.
 *
 * Authentication here is entirely JWT based: the token is verified by the shared filter and the
 * principal comes from its claims. Spring Boot would otherwise create a default in-memory user and
 * print a generated password to the log on every start - a credential nobody uses, advertised in
 * plain text at INFO level on every instance.
 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class, scanBasePackages = {
        "org.retailbank360.client",
        "org.retailbank360.config",
        "org.retailbank360.controller",
        "org.retailbank360.filter",
        "org.retailbank360.job",
        "org.retailbank360.repository",
        "org.retailbank360.service"
})
public class GatewayAppProcessor {

    public static void main(String[] args) {
        SpringApplication.run(GatewayAppProcessor.class, args);
    }
}
