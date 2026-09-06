package org.retailbank360.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the login and transfer rate limiter. */
class SlidingWindowRateLimiterTest {

    @Test
    @DisplayName("Requests are allowed up to the limit and refused after it")
    void enforcesTheLimit() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        Duration window = Duration.ofSeconds(10);

        for (int attempt = 1; attempt <= 5; attempt++) {
            SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire("login|1.2.3.4", 5, window);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(5 - attempt);
        }

        SlidingWindowRateLimiter.Decision refused = limiter.tryAcquire("login|1.2.3.4", 5, window);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("Each caller gets its own budget")
    void isolatesCallers() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        Duration window = Duration.ofSeconds(10);

        assertThat(limiter.tryAcquire("login|attacker", 1, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("login|attacker", 1, window).allowed()).isFalse();

        // One caller exhausting its budget must not lock everybody else out.
        assertThat(limiter.tryAcquire("login|honest-user", 1, window).allowed()).isTrue();
    }

    @Test
    @DisplayName("The budget refills once the window has passed")
    void refillsAfterTheWindow() throws InterruptedException {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        Duration window = Duration.ofMillis(200);

        assertThat(limiter.tryAcquire("k", 1, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 1, window).allowed()).isFalse();

        Thread.sleep(250);
        assertThat(limiter.tryAcquire("k", 1, window).allowed()).isTrue();
    }
}
