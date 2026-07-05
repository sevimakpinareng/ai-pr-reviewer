package com.example.prreviewer.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Tracks the latest commit seen per PR so a job for an older commit can be
 * skipped when newer pushes have arrived (avoids wasting an analysis + posting
 * a review on a commit the author already replaced).
 * <p>
 * The producer records the latest commit at publish time, so by the time a
 * consumer picks up a job the "latest" value already reflects any newer push.
 * Fails open: if Redis is unavailable we do NOT skip (process the job).
 */
@Service
public class PrSupersedingService {

    private static final Logger log = LoggerFactory.getLogger(PrSupersedingService.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public PrSupersedingService(StringRedisTemplate redis,
                                @Value("${app.supersede.ttl:2d}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** Records the newest commit for a PR. Call from the producer before publishing. */
    public void recordLatestCommit(String owner, String repo, int prNumber, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(key(owner, repo, prNumber), commitSha, ttl);
        } catch (RuntimeException e) {
            log.warn("Could not record latest commit (Redis) for {}/{} #{}: {}",
                    owner, repo, prNumber, e.getMessage());
        }
    }

    /**
     * True if a newer commit for this PR has been seen (this job is stale).
     * Unknown PR (no record) counts as NOT stale, so we don't skip real work.
     */
    public boolean isStale(String owner, String repo, int prNumber, String commitSha) {
        try {
            String latest = redis.opsForValue().get(key(owner, repo, prNumber));
            if (latest == null) {
                return false;
            }
            return !latest.equals(commitSha);
        } catch (RuntimeException e) {
            log.warn("Superseding check unavailable (Redis); processing anyway (fail-open): {}", e.getMessage());
            return false;
        }
    }

    private static String key(String owner, String repo, int prNumber) {
        return "pr:latest:" + owner + "/" + repo + "#" + prNumber;
    }
}
