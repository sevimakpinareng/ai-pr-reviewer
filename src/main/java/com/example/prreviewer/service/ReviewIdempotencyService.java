package com.example.prreviewer.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Delivery-based dedupe. The mark is written only AFTER a delivery is processed
 * successfully, so an in-flight/transient failure can still be retried, but a
 * delivery that already completed is never processed again.
 * <p>
 * Fails open: if Redis is unavailable we let the work proceed (risking at worst
 * a duplicate review) rather than silently dropping it.
 */
@Service
public class ReviewIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(ReviewIdempotencyService.class);
    private static final String KEY_PREFIX = "dedupe:delivery:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ReviewIdempotencyService(StringRedisTemplate redis,
                                    @Value("${app.dedupe.ttl:6h}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** True if this delivery has already been processed to completion. */
    public boolean isAlreadyProcessed(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return false; // nothing to dedupe on -> process
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + deliveryId));
        } catch (RuntimeException e) {
            log.warn("Dedupe check unavailable (Redis); processing anyway (fail-open): {}", e.getMessage());
            return false;
        }
    }

    /** Records that this delivery finished; call only on successful processing. */
    public void markProcessed(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + deliveryId, "1", ttl);
        } catch (RuntimeException e) {
            log.warn("Could not record dedupe mark (Redis) for delivery {}: {}", deliveryId, e.getMessage());
        }
    }
}
