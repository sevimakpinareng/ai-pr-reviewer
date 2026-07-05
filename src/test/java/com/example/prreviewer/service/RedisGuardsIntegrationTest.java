package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Redis semantics for the three guards, against a Testcontainers Redis.
 * Wires the services directly (no Spring context) for a fast, focused check.
 */
@Testcontainers
class RedisGuardsIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @BeforeAll
    static void startFactory() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void stopFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void freshRedis() {
        redis = new StringRedisTemplate(connectionFactory);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void dedupeRemembersProcessedDeliveries() {
        ReviewIdempotencyService dedupe = new ReviewIdempotencyService(redis, Duration.ofMinutes(10));

        assertFalse(dedupe.isAlreadyProcessed("delivery-a"));
        dedupe.markProcessed("delivery-a");
        assertTrue(dedupe.isAlreadyProcessed("delivery-a"));
        assertFalse(dedupe.isAlreadyProcessed("delivery-b"));
    }

    @Test
    void supersedingDetectsOlderCommits() {
        PrSupersedingService superseding = new PrSupersedingService(redis, Duration.ofMinutes(10));

        superseding.recordLatestCommit("octocat", "Hello-World", 42, "sha-new");

        assertTrue(superseding.isStale("octocat", "Hello-World", 42, "sha-old"));
        assertFalse(superseding.isStale("octocat", "Hello-World", 42, "sha-new"));
        // Unknown PR -> not stale (don't skip real work).
        assertFalse(superseding.isStale("octocat", "Hello-World", 99, "whatever"));
    }

    @Test
    void rateLimiterEnforcesFixedWindow() throws InterruptedException {
        ReviewRateLimiter limiter = new ReviewRateLimiter(redis, 3, Duration.ofSeconds(1));

        assertTrue(limiter.tryAcquire(7L));
        assertTrue(limiter.tryAcquire(7L));
        assertTrue(limiter.tryAcquire(7L));
        assertFalse(limiter.tryAcquire(7L), "4th call in the window is limited");

        // A different installation has its own budget.
        assertTrue(limiter.tryAcquire(8L));

        // After the window expires, the counter resets.
        Thread.sleep(1200);
        assertTrue(limiter.tryAcquire(7L), "window reset allows again");
    }
}
