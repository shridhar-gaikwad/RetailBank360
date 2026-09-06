package org.retailbank360;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Consumer loan servicing.
 *
 * <p>Owns loan applications, eligibility scoring, the amortisation schedule and repayment posting.
 * Money is never held here: disbursement credits the customer's account, and a repayment debits it,
 * both through the internal ledger API of account-service.</p>
 *
 * <p>Health: {@code http://localhost:8085/actuator/health} - Swagger: {@code /swagger-ui.html}</p>
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
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class, scanBasePackages = {
        "org.retailbank360.client",
        "org.retailbank360.config",
        "org.retailbank360.controller",
        "org.retailbank360.filter",
        "org.retailbank360.job",
        "org.retailbank360.repository",
        "org.retailbank360.service"
})
@EnableFeignClients
public class LoanServiceAppProcessor {

    public static void main(String[] args) {
        SpringApplication.run(LoanServiceAppProcessor.class, args);
    }
}
