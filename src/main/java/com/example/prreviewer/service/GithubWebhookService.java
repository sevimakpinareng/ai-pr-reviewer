package com.example.prreviewer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.dto.ReviewJobMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies GitHub webhook signatures and turns reviewable events into queued
 * jobs. This is the producer side: it does NO heavy work (no diff fetch, no
 * analysis, no DB, no GitHub calls). For a relevant pull_request it publishes a
 * small {@link ReviewJobMessage} and returns, so the webhook can answer fast.
 */
@Service
public class GithubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /** pull_request actions we care about; everything else is ignored. */
    private static final Set<String> RELEVANT_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final String webhookSecret;

    public GithubWebhookService(ObjectMapper objectMapper,
                                RabbitTemplate rabbitTemplate,
                                @Value("${github.webhook.secret:}") String webhookSecret) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Validates the {@code X-Hub-Signature-256} header against an HMAC-SHA256 of
     * the <em>raw</em> request body, using a constant-time comparison.
     *
     * @param rawBody         the exact bytes GitHub sent (never a re-serialized form)
     * @param signatureHeader the header value, expected as {@code "sha256=<hex>"}
     * @return true only if the signature is present, well-formed, and matches
     */
    public boolean isValidSignature(byte[] rawBody, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("GITHUB_WEBHOOK_SECRET is not configured; rejecting all webhooks");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        String expected = SIGNATURE_PREFIX + hmacSha256Hex(rawBody);

        // Constant-time comparison: avoids leaking how many leading characters
        // matched, which a byte-by-byte (short-circuiting) compare would reveal.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Handles an already-authenticated event. For a relevant {@code pull_request}
     * it publishes one {@link ReviewJobMessage}; everything else is just logged.
     *
     * @param deliveryId the {@code X-GitHub-Delivery} id, carried through for tracing
     */
    public void handleEvent(String eventType, String deliveryId, byte[] rawBody) {
        if (eventType == null || eventType.isBlank()) {
            log.warn("Webhook with missing X-GitHub-Event header (delivery={}); ignoring", deliveryId);
            return;
        }

        if (!"pull_request".equals(eventType)) {
            log.info("alındı: {} (delivery={})", eventType, deliveryId);
            return;
        }

        PullRequestInfo info = parsePullRequest(rawBody);
        if (!RELEVANT_ACTIONS.contains(info.action())) {
            log.info("pull_request action '{}' ignored (repo={} pr=#{} delivery={})",
                    info.action(), info.repoFullName(), info.prNumber(), deliveryId);
            return;
        }
        if (info.owner() == null || info.repo() == null || info.installationId() <= 0) {
            log.warn("pull_request missing installation/repo info; not queued (repo={} delivery={})",
                    info.repoFullName(), deliveryId);
            return;
        }

        ReviewJobMessage job = new ReviewJobMessage(
                info.installationId(), info.owner(), info.repo(), (int) info.prNumber(),
                info.headSha(), info.action(), deliveryId);
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, job);
        log.info("Queued review job: repo={} pr=#{} action={} delivery={}",
                info.repoFullName(), info.prNumber(), info.action(), deliveryId);
    }

    /**
     * Extracts the handful of fields we currently need from a pull_request payload.
     * Package-visible return type keeps this directly unit-testable.
     */
    public PullRequestInfo parsePullRequest(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String action = root.path("action").asText(null);
            JsonNode repository = root.path("repository");
            String repoFullName = repository.path("full_name").asText(null);
            String defaultBranch = repository.path("default_branch").asText(null);
            String accountLogin = repository.path("owner").path("login").asText(null);
            long prNumber = root.path("number").asLong();
            String headSha = root.path("pull_request").path("head").path("sha").asText(null);
            long installationId = root.path("installation").path("id").asLong();
            return new PullRequestInfo(repoFullName, prNumber, action, headSha, installationId,
                    accountLogin, defaultBranch);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed webhook JSON payload", e);
        }
    }

    private String hmacSha256Hex(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    /** The subset of a pull_request event we currently use. */
    public record PullRequestInfo(String repoFullName, long prNumber, String action, String headSha,
                                  long installationId, String accountLogin, String defaultBranch) {

        /** Owner portion of "owner/repo", or null if unparseable. */
        public String owner() {
            return splitFullName(0);
        }

        /** Repository portion of "owner/repo", or null if unparseable. */
        public String repo() {
            return splitFullName(1);
        }

        private String splitFullName(int index) {
            if (repoFullName == null) {
                return null;
            }
            String[] parts = repoFullName.split("/", 2);
            return parts.length == 2 ? parts[index] : null;
        }
    }
}
