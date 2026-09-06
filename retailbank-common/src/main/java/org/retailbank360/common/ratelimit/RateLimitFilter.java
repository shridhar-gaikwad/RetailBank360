package org.retailbank360.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.RateLimitProperties;
import org.retailbank360.common.dto.ErrorResponse;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.web.CorrelationIdFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the configured {@code retailbank.rate-limit.rules} to incoming requests.
 *
 * <p>Two endpoint families matter for this system: login (password guessing) and transfers (a
 * runaway client or a scripted drain). Both are configured per service in {@code application.yml}
 * rather than hard-coded here.</p>
 *
 * <p>Callers are keyed by authenticated username when a token is present and by client IP otherwise,
 * so an anonymous login flood is still contained. The filter runs after authentication so the
 * username is available, and returns 429 with a {@code Retry-After} header.</p>
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    private final RateLimitProperties properties;
    private final SlidingWindowRateLimiter limiter;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitProperties properties, SlidingWindowRateLimiter limiter,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            if (!matches(rule, request, path)) {
                continue;
            }
            String key = rule.getPathPattern() + "|" + callerKey(request);
            SlidingWindowRateLimiter.Decision decision =
                    limiter.tryAcquire(key, rule.getLimit(), rule.getWindow());

            if (!decision.allowed()) {
                log.warn("Rate limit hit: {} {} by {} (limit {} per {})",
                        request.getMethod(), path, callerKey(request), rule.getLimit(), rule.getWindow());
                writeTooManyRequests(request, response, rule, decision.retryAfterSeconds());
                return;
            }
            response.setHeader("X-RateLimit-Limit", String.valueOf(rule.getLimit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
            break;
        }

        chain.doFilter(request, response);
    }

    private boolean matches(RateLimitProperties.Rule rule, HttpServletRequest request, String path) {
        if (!StringUtils.hasText(rule.getPathPattern()) || !pathMatcher.match(rule.getPathPattern(), path)) {
            return false;
        }
        return !StringUtils.hasText(rule.getMethod())
                || rule.getMethod().equalsIgnoreCase(request.getMethod());
    }

    /** Authenticated username when known, otherwise the originating IP. */
    private String callerKey(HttpServletRequest request) {
        return SecurityUtils.currentUser()
                .map(user -> "user:" + user.username())
                .orElseGet(() -> "ip:" + clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                      RateLimitProperties.Rule rule, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Limit", String.valueOf(rule.getLimit()));
        response.setHeader("X-RateLimit-Remaining", "0");

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "RATE_LIMIT_EXCEEDED",
                "Too many requests to " + request.getRequestURI() + ". Limit is " + rule.getLimit()
                        + " per " + rule.getWindow().toSeconds() + "s. Retry in " + retryAfterSeconds + "s.",
                request.getRequestURI(),
                CorrelationIdFilter.currentCorrelationId());
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /** Runs after the JWT filter so the authenticated username can key the counter. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }
}
