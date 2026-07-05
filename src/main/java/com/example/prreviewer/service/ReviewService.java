package com.example.prreviewer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.example.prreviewer.dto.DiffFile;
import com.example.prreviewer.dto.ReviewContext;
import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;
import com.example.prreviewer.metrics.ReviewMetrics;

import io.micrometer.core.instrument.Timer;

/**
 * Sends a diff to an LLM (via Spring AI), file by file, and returns structured
 * findings.
 * <p>
 * The diff is split per file (see {@link DiffParser}) and each file is analyzed
 * in its own model call. This keeps each prompt small and focused, isolates one
 * file's failure from the rest, and keeps every finding tied to a known file.
 * Noise is then controlled at the aggregate level: a severity threshold and a
 * hard cap on the number of findings (most severe kept first).
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** Log a warning once the LLM has failed this many times in a row. */
    private static final int LLM_UNHEALTHY_STREAK = 5;

    private final ChatClient chatClient;
    private final DiffParser diffParser;
    private final ReviewMetrics metrics;
    private final String systemPrompt;
    private final int maxDiffChars;
    private final Severity severityThreshold;
    private final int maxFindings;

    // Lightweight health signal: consecutive LLM failures (not a full breaker).
    private final AtomicInteger consecutiveLlmErrors = new AtomicInteger();

    public ReviewService(ChatClient.Builder chatClientBuilder,
                         DiffParser diffParser,
                         ReviewMetrics metrics,
                         @Value("classpath:prompts/review-system.txt") Resource systemPromptResource,
                         @Value("${app.review.max-diff-chars:12000}") int maxDiffChars,
                         @Value("${app.review.severity-threshold:WARNING}") Severity severityThreshold,
                         @Value("${app.review.max-findings:10}") int maxFindings) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.diffParser = diffParser;
        this.metrics = metrics;
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.maxDiffChars = maxDiffChars;
        this.severityThreshold = severityThreshold;
        this.maxFindings = maxFindings;
    }

    /**
     * Reviews a raw unified diff, one file at a time.
     *
     * @throws ReviewFailedException if there were files to review but every one
     *                               failed to analyze
     */
    public ReviewResult review(String rawDiff, ReviewContext context) {
        List<DiffFile> files = diffParser.parse(rawDiff);
        if (files.isEmpty()) {
            return ReviewResult.empty("No reviewable files in diff.");
        }

        List<ReviewFinding> collected = new ArrayList<>();
        List<String> perFileSummaries = new ArrayList<>();
        int analyzed = 0;
        int failed = 0;

        for (DiffFile file : files) {
            try {
                ReviewResult fileResult = analyzeFile(file);
                analyzed++;
                if (fileResult.summary() != null && !fileResult.summary().isBlank()) {
                    perFileSummaries.add(file.filePath() + ": " + fileResult.summary().strip());
                }
                if (fileResult.findings() != null) {
                    for (ReviewFinding finding : fileResult.findings()) {
                        // Force the path to the file we actually analyzed, so a
                        // finding can never be attributed to the wrong file.
                        collected.add(withFilePath(finding, file.filePath()));
                    }
                }
            } catch (Exception e) {
                failed++;
                log.warn("Skipping file '{}' for {} #{}: {}",
                        file.filePath(), context.repoFullName(), context.prNumber(), e.getMessage());
            }
        }

        if (analyzed == 0) {
            throw new ReviewFailedException(
                    "All " + failed + " file(s) failed analysis for "
                            + context.repoFullName() + " #" + context.prNumber());
        }

        List<ReviewFinding> finalFindings = collected.stream()
                .filter(f -> f.severity() != null && f.severity().ordinal() >= severityThreshold.ordinal())
                // Most severe first, so the cap keeps the important findings.
                .sorted(Comparator.comparingInt((ReviewFinding f) -> f.severity().ordinal()).reversed())
                .limit(maxFindings)
                .toList();

        log.info("Review of {} #{}: {} file(s) analyzed, {} failed, {} finding(s) kept",
                context.repoFullName(), context.prNumber(), analyzed, failed, finalFindings.size());

        String summary = perFileSummaries.isEmpty()
                ? "No significant issues found."
                : String.join("\n", perFileSummaries);
        return new ReviewResult(summary, finalFindings);
    }

    private ReviewResult analyzeFile(DiffFile file) {
        String diff = file.diff();
        if (diff.length() > maxDiffChars) {
            log.warn("File '{}' diff truncated from {} to {} chars (chunking comes later)",
                    file.filePath(), diff.length(), maxDiffChars);
            diff = diff.substring(0, maxDiffChars);
        }

        Timer.Sample sample = metrics.startSample();
        try {
            ReviewResult result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(diff)
                    .call()
                    .entity(ReviewResult.class);
            if (result == null) {
                throw new IllegalStateException("Model returned no structured result");
            }
            consecutiveLlmErrors.set(0);
            return result;
        } catch (RuntimeException e) {
            metrics.recordLlmError();
            int streak = consecutiveLlmErrors.incrementAndGet();
            if (streak >= LLM_UNHEALTHY_STREAK) {
                log.warn("LLM has failed {} times in a row; it may be unhealthy", streak);
            }
            throw e;
        } finally {
            metrics.stopLlmCall(sample);
        }
    }

    private static ReviewFinding withFilePath(ReviewFinding finding, String filePath) {
        return new ReviewFinding(filePath, finding.line(), finding.severity(), finding.title(), finding.detail());
    }
}
