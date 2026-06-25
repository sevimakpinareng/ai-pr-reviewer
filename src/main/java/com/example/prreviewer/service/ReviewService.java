package com.example.prreviewer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;

/**
 * Sends a diff to an LLM (via Spring AI) and returns structured review findings.
 * <p>
 * Noise is controlled in three layers: a low-noise system prompt, a severity
 * threshold, and a hard cap on the number of findings returned.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ChatClient chatClient;
    private final String systemPrompt;
    private final int maxDiffChars;
    private final Severity severityThreshold;
    private final int maxFindings;

    public ReviewService(ChatClient.Builder chatClientBuilder,
                         @Value("classpath:prompts/review-system.txt") Resource systemPromptResource,
                         @Value("${app.review.max-diff-chars:12000}") int maxDiffChars,
                         @Value("${app.review.severity-threshold:WARNING}") Severity severityThreshold,
                         @Value("${app.review.max-findings:10}") int maxFindings) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.maxDiffChars = maxDiffChars;
        this.severityThreshold = severityThreshold;
        this.maxFindings = maxFindings;
    }

    /**
     * Reviews a raw unified diff. Never throws: model/parse failures degrade to
     * an empty result with a warning log so the caller stays up.
     */
    public ReviewResult review(String diff) {
        if (diff == null || diff.isBlank()) {
            return ReviewResult.empty("Empty diff; nothing to review.");
        }

        String boundedDiff = diff;
        if (diff.length() > maxDiffChars) {
            boundedDiff = diff.substring(0, maxDiffChars);
            log.warn("Diff truncated from {} to {} chars before review (chunking comes later)",
                    diff.length(), maxDiffChars);
        }

        ReviewResult raw;
        try {
            raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(boundedDiff)
                    .call()
                    .entity(ReviewResult.class);
        } catch (Exception e) {
            log.warn("LLM review failed or returned unparseable output: {}", e.getMessage());
            return ReviewResult.empty("Review could not be completed.");
        }

        if (raw == null) {
            log.warn("LLM returned no structured result");
            return ReviewResult.empty("Review could not be completed.");
        }

        List<ReviewFinding> findings = raw.findings() == null ? List.of() : raw.findings();
        List<ReviewFinding> filtered = findings.stream()
                .filter(f -> f.severity() != null && f.severity().ordinal() >= severityThreshold.ordinal())
                // Most severe first, so the cap keeps the important findings.
                .sorted(Comparator.comparingInt((ReviewFinding f) -> f.severity().ordinal()).reversed())
                .limit(maxFindings)
                .toList();

        if (filtered.size() < findings.size()) {
            log.debug("Filtered findings from {} to {} (threshold={}, cap={})",
                    findings.size(), filtered.size(), severityThreshold, maxFindings);
        }

        return new ReviewResult(raw.summary(), filtered);
    }
}
