package org.retailbank360.config;

import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.filter.JwtAuthenticationWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Reactive security for the gateway.
 *
 * <p>Only the login family and the documentation endpoints are open. Everything else needs a valid
 * token, verified by {@link JwtAuthenticationWebFilter} before routing. Role decisions stay with the
 * services that own the data, so this chain checks authentication only.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public JwtAuthenticationWebFilter jwtAuthenticationWebFilter(JwtTokenService tokenService) {
        return new JwtAuthenticationWebFilter(tokenService);
    }

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http,
                                                                JwtAuthenticationWebFilter jwtFilter) {
        return http
                // Stateless bearer-token API: no session, no browser form, so CSRF adds nothing.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .pathMatchers("/api/v1/auth/login", "/api/v1/auth/mfa/verify",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(jwtFilter, org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    /**
     * Permissive CORS for the POC so a local UI can call the gateway.
     *
     * <p>Origins would be pinned to the actual front-end hosts in any real deployment; credentials are
     * left off precisely because the wildcard origin is in use here.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After",
                "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
