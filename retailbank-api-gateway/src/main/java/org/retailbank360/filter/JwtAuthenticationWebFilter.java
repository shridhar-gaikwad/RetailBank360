package org.retailbank360.filter;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.security.AuthenticatedUser;
import org.retailbank360.common.security.JwtTokenService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Verifies the bearer token at the edge.
 *
 * <p>Rejecting a bad token here saves a hop, and gives the client one consistent 401 whichever
 * service it was heading for. It does not replace the checks downstream: every service verifies the
 * token again and applies its own role rules, so the gateway is a convenience and a first line of
 * defence, never the only one.</p>
 *
 * <p>The filter also assigns a correlation id when the caller did not supply one, so a request can be
 * traced from the edge through every service it touches.</p>
 */
@Slf4j
public class JwtAuthenticationWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final JwtTokenService tokenService;

    public JwtAuthenticationWebFilter(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange traced = withCorrelationId(exchange);
        String header = traced.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            // No credentials at all: the authorization rules decide whether that is acceptable.
            return chain.filter(traced);
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        return tokenService.authenticate(token)
                .map(user -> chain.filter(traced).contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(toAuthentication(user))))
                .orElseGet(() -> unauthorized(traced));
    }

    private UsernamePasswordAuthenticationToken toAuthentication(AuthenticatedUser user) {
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(Roles.ROLE_PREFIX + user.role())));
    }

    /** Ensures every request carries a correlation id from the edge inward. */
    private ServerWebExchange withCorrelationId(ServerWebExchange exchange) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
        if (StringUtils.hasText(correlationId)) {
            exchange.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);
            return exchange;
        }
        String generated = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(CORRELATION_HEADER, generated);
        return exchange.mutate()
                .request(builder -> builder.header(CORRELATION_HEADER, generated))
                .build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        log.debug("Rejected an unusable bearer token for {}", exchange.getRequest().getPath());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"status":401,"error":"Unauthorized","errorCode":"INVALID_TOKEN",\
                "message":"The bearer token is missing, malformed or expired","path":"%s"}"""
                .formatted(exchange.getRequest().getPath().value());

        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
