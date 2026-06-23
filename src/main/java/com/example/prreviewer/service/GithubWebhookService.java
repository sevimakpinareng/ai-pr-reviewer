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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies GitHub webhook signatures and dispatches events.
 * <p>
 * All HTTP plumbing lives in the controller; this class owns the security
 * (signature check) and the event-handling logic so it can be unit-tested
 * without a running web server.
 */
@Service
public class GithubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /** pull_request actions we care about; everything else is ignored. */
    private static final Set<String> RELEVANT_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public GithubWebhookService(ObjectMapper objectMapper,
                                @Value("${github.webhook.secret:}") String webhookSecret) {
        this.objectMapper = objectMapper;
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
     * Handles an already-authenticated event. Only {@code pull_request} events
     * are inspected; everything else is just acknowledged in the log.
     */
    public void handleEvent(String eventType, byte[] rawBody) {
        if (eventType == null || eventType.isBlank()) {
            log.warn("Webhook with missing X-GitHub-Event header; ignoring");
            return;
        }

        if (!"pull_request".equals(eventType)) {
            log.info("alındı: {}", eventType);
            return;
        }

        PullRequestInfo info = parsePullRequest(rawBody);
        if (RELEVANT_ACTIONS.contains(info.action())) {
            log.info("pull_request {} -> repo={} pr=#{} headSha={}",
                    info.action(), info.repoFullName(), info.prNumber(), info.headSha());
        } else {
            log.info("pull_request action '{}' ignored (repo={} pr=#{})",
                    info.action(), info.repoFullName(), info.prNumber());
        }
    }

    /**
     * Extracts the handful of fields we currently need from a pull_request payload.
     * Package-visible return type keeps this directly unit-testable.
     */
    public PullRequestInfo parsePullRequest(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String action = root.path("action").asText(null);
            String repoFullName = root.path("repository").path("full_name").asText(null);
            long prNumber = root.path("number").asLong();
            String headSha = root.path("pull_request").path("head").path("sha").asText(null);
            return new PullRequestInfo(repoFullName, prNumber, action, headSha);
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

    /** The subset of a pull_request event we currently log. */
    public record PullRequestInfo(String repoFullName, long prNumber, String action, String headSha) {
    }
}
