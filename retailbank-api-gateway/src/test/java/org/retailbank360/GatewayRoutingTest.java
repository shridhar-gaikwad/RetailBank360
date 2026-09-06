package org.retailbank360;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.filter.JwtAuthenticationWebFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway wiring.
 *
 * <p>The gateway is the one reactive application in the platform, and it depends on the same shared
 * module as the servlet services. The servlet-only auto-configurations in retailbank-common are
 * guarded on {@code @ConditionalOnWebApplication(SERVLET)} precisely so they back off here; a context
 * that starts is the assertion that they do. The routes themselves are checked because a typo in
 * {@code application.yml} would otherwise only surface as a 404 at runtime.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayRoutingTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Every service is routable through the gateway")
    void publishesARouteForEveryService() {
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(routeIds).containsExactlyInAnyOrder(
                "auth-service",
                "customer-service",
                "account-service",
                "transaction-service",
                "loan-service",
                "notification-service",
                "audit-service",
                "lock-admin");
    }

    @Test
    @DisplayName("Reactive security is wired, and the servlet-only shared beans stay absent")
    void wiresReactiveSecurityOnly() {
        assertThat(applicationContext.getBean(SecurityWebFilterChain.class)).isNotNull();
        assertThat(applicationContext.getBean(JwtAuthenticationWebFilter.class)).isNotNull();

        // These come from retailbank-common and are conditional on a servlet stack. If either leaked
        // into this reactive context the application would not have started at all.
        assertThat(applicationContext.containsBean("jwtAuthenticationFilter")).isFalse();
        assertThat(applicationContext.containsBean("globalExceptionHandler")).isFalse();
        assertThat(applicationContext.containsBean("rateLimitFilter")).isFalse();

        // The token service is shared on purpose: the gateway and every service must agree on it.
        assertThat(applicationContext.getBean(JwtTokenService.class)).isNotNull();
    }
}
