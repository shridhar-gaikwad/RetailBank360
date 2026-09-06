package org.retailbank360.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code retailbank.rate-limit.*}.
 *
 * <p>Each rule limits one Ant path pattern to {@code limit} requests per {@code window} for a given
 * caller. Callers are identified by the authenticated username when present, otherwise by client IP,
 * so an unauthenticated login flood is still contained.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "retailbank.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private List<Rule> rules = new ArrayList<>();

    @Getter
    @Setter
    public static class Rule {

        /** Ant path pattern, e.g. {@code /api/v1/auth/login}. */
        private String pathPattern;

        /** Restrict the rule to one HTTP method; empty means every method. */
        private String method = "";

        private int limit = 10;

        private Duration window = Duration.ofMinutes(1);
    }
}
