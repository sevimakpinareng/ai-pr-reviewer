package com.example.prreviewer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
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

    /** How many leading diff lines to log as a preview. */
    private static final int DIFF_PREVIEW_LINES = 30;

    private final ObjectMapper objectMapper;
    private final GithubApiClient githubApiClient;
    private final ReviewProcessingService reviewProcessingService;
    private final String webhookSecret;

    public GithubWebhookService(ObjectMapper objectMapper,
                                GithubApiClient githubApiClient,
                                ReviewProcessingService reviewProcessingService,
                                @Value("${github.webhook.secret:}") String webhookSecret) {
        this.objectMapper = objectMapper;
        this.githubApiClient = githubApiClient;
        this.reviewProcessingService = reviewProcessingService;
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
        if (!RELEVANT_ACTIONS.contains(info.action())) {
            log.info("pull_request action '{}' ignored (repo={} pr=#{})",
                    info.action(), info.repoFullName(), info.prNumber());
            return;
        }

        log.info("pull_request {} -> repo={} pr=#{} headSha={}",
                info.action(), info.repoFullName(), info.prNumber(), info.headSha());
        fetchAndLogDiff(info);
    }

    /**
     * Best-effort diff fetch. Any failure (auth, network, GitHub error) is
     * logged and swallowed so a single bad delivery never takes the app down.
     * There is no queue/retry yet — that comes in a later step.
     */
    private void fetchAndLogDiff(PullRequestInfo info) {
        String owner = info.owner();
        String repo = info.repo();
        if (owner == null || repo == null || info.installationId() <= 0) {
            log.warn("Cannot fetch diff: missing installation/repo info (repo={} installationId={})",
                    info.repoFullName(), info.installationId());
            return;
        }

        try {
            String diff = githubApiClient.fetchPullRequestDiff(
                    info.installationId(), owner, repo, (int) info.prNumber());
            int byteSize = diff == null ? 0 : diff.getBytes(StandardCharsets.UTF_8).length;
            log.info("Fetched diff for {} #{}: {} bytes", info.repoFullName(), info.prNumber(), byteSize);
            if (diff != null && !diff.isEmpty()) {
                log.info("Diff preview ({} #{}), first {} lines:\n{}",
                        info.repoFullName(), info.prNumber(), DIFF_PREVIEW_LINES, previewLines(diff));
                // Hand off to the review + persistence orchestration.
                reviewProcessingService.processPullRequest(info, diff);
            }
        } catch (Exception e) {
            log.error("Failed to fetch diff for {} #{}: {}",
                    info.repoFullName(), info.prNumber(), e.getMessage(), e);
        }
    }

    private static String previewLines(String diff) {
        String[] lines = diff.split("\n", -1);
        int count = Math.min(DIFF_PREVIEW_LINES, lines.length);
        return String.join("\n", Arrays.copyOfRange(lines, 0, count));
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
