package org.retailbank360.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.dto.ErrorResponse;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Verifies the {@code Authorization: Bearer} token on every request and populates the security
 * context with an {@link AuthenticatedUser}.
 *
 * <p>A missing token is left to the authorization layer, which answers 401 for protected paths and
 * lets public paths through. A token that is present but unusable (bad signature, expired, wrong
 * issuer) short circuits with an explicit 401 payload so the client can tell the two cases apart.</p>
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        var user = tokenService.authenticate(token);
        if (user.isEmpty()) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(request, response);
            return;
        }

        AuthenticatedUser principal = user.get();
        var authorities = List.of(new SimpleGrantedAuthority(Roles.ROLE_PREFIX + principal.role()));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("Authenticated {} for {} {}", principal, request.getMethod(), request.getRequestURI());

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "INVALID_TOKEN",
                "The bearer token is missing, malformed or expired",
                request.getRequestURI(),
                CorrelationIdFilter.currentCorrelationId());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
