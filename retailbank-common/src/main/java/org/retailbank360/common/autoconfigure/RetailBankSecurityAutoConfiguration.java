package org.retailbank360.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.retailbank360.common.config.SecurityProperties;
import org.retailbank360.common.dto.ErrorResponse;
import org.retailbank360.common.security.JwtAuthenticationFilter;
import org.retailbank360.common.security.JwtTokenService;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Stateless JWT security shared by every servlet-based service.
 *
 * <p>One chain covers all of them; the differences between services are expressed purely as
 * configuration ({@code retailbank.security.public-paths}), so the auth-service can open its login
 * endpoints without duplicating a security configuration class. Fine-grained role checks live on the
 * controllers as {@code @PreAuthorize}, and row-level ownership checks live in
 * {@code SecurityUtils.requireCustomerAccess}.</p>
 *
 * <p>Backs off entirely when {@code retailbank.security.enabled=false}, which slice tests use.</p>
 */
@AutoConfiguration(after = RetailBankCoreAutoConfiguration.class)
@ConditionalOnClass({SecurityFilterChain.class, HttpSecurity.class, HttpServletRequest.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableWebSecurity
@EnableMethodSecurity
public class RetailBankSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService tokenService,
                                                            ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(tokenService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain retailBankSecurityFilterChain(HttpSecurity http,
                                                             JwtAuthenticationFilter jwtAuthenticationFilter,
                                                             SecurityProperties properties,
                                                             ObjectMapper objectMapper) throws Exception {

        http
                // Stateless bearer-token API: there is no session and no browser form to protect,
                // so CSRF protection would only break clients.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Same-origin frames so the H2 console works on the local profile.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> {
                    if (!properties.isEnabled()) {
                        auth.anyRequest().permitAll();
                        return;
                    }
                    for (String publicPath : properties.getPublicPaths()) {
                        auth.requestMatchers(publicPath).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** 401 in the same JSON shape as every other error. */
    private AuthenticationEntryPoint unauthorizedEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> write(objectMapper, request, response,
                HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is required to access this resource", authException);
    }

    /** 403 in the same JSON shape as every other error. */
    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, deniedException) -> write(objectMapper, request, response,
                HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have the role required for this operation", deniedException);
    }

    private void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String code, String message, Exception cause) throws IOException {
        if (cause instanceof AuthenticationException || cause instanceof AccessDeniedException) {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                    status.value(), status.getReasonPhrase(), code, message,
                    request.getRequestURI(), CorrelationIdFilter.currentCorrelationId()));
        }
    }
}
