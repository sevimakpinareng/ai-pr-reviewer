package com.example.prreviewer.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Fixed-window rate limiter, one counter per installation.
 * <p>
 * INCR + EXPIRE run in a single Lua script so the TTL is set atomically on the
 * first request of a window; the key then expires on its own, resetting the
 * window. Fails open: if Redis is unavailable we allow the request rather than
 * stalling all processing.
 */
@Service
public class ReviewRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ReviewRateLimiter.class);

    // Set the window TTL only when the counter is first created (== 1).
    private static final RedisScript<Long> INCR_WITH_EXPIRE = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final int limit;
    private final long windowSeconds;

    public ReviewRateLimiter(StringRedisTemplate redis,
                             @Value("${app.ratelimit.limit:30}") int limit,
                             @Value("${app.ratelimit.window:1m}") Duration window) {
        this.redis = redis;
        this.limit = limit;
        this.windowSeconds = Math.max(1, window.toSeconds());
    }

    /** True if a review is allowed for this installation in the current window. */
    public boolean tryAcquire(long installationId) {
        String key = "ratelimit:install:" + installationId;
        try {
            Long count = redis.execute(INCR_WITH_EXPIRE, List.of(key), String.valueOf(windowSeconds));
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable (Redis); allowing request (fail-open): {}", e.getMessage());
            return true;
        }
    }
}
