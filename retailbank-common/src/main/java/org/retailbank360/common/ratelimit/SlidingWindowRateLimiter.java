package org.retailbank360.common.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding window rate limiter.
 *
 * <p>Keeps the timestamps of the recent requests per key and drops the ones that fall out of the
 * window. Exact (unlike a fixed window, which lets twice the budget through at a window boundary)
 * and dependency free.</p>
 *
 * <p>State is per JVM. That is the right granularity for a login brute-force guard behind a small
 * number of instances; a multi-node deployment that needs a single global budget would swap this
 * implementation for a shared counter without touching the filter that calls it.</p>
 */
public class SlidingWindowRateLimiter {

    /** Above this many tracked keys, idle entries are pruned before admitting a new one. */
    private static final int PRUNE_THRESHOLD = 10_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Registers one request against {@code key}.
     *
     * @return a decision carrying whether the request is allowed and, if not, when to retry
     */
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();

        if (hits.size() > PRUNE_THRESHOLD) {
            prune(now, windowMillis);
        }

        Deque<Long> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                long retryAfterMillis = windowMillis - (now - timestamps.peekFirst());
                return new Decision(false, Math.max(1, (retryAfterMillis + 999) / 1000), 0);
            }
            timestamps.addLast(now);
            return new Decision(true, 0, limit - timestamps.size());
        }
    }

    /** Drops keys whose entire history has aged out of the window. */
    private void prune(long now, long windowMillis) {
        hits.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
                    timestamps.pollFirst();
                }
                return timestamps.isEmpty();
            }
        });
    }

    /** Clears all counters. Used by tests and by an administrative reset. */
    public void reset() {
        hits.clear();
    }

    /**
     * @param allowed           whether the request may proceed
     * @param retryAfterSeconds how long the caller should wait, meaningful only when rejected
     * @param remaining         requests still available in the current window
     */
    public record Decision(boolean allowed, long retryAfterSeconds, int remaining) {
    }
}
