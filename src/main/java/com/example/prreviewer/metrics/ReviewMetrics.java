package com.example.prreviewer.metrics;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Central place for the bot's business metrics, so names and tags stay
 * consistent and low-cardinality. Meter names are Micrometer-idiomatic (no
 * redundant {@code .total}/{@code .count} suffix); the Prometheus registry
 * renders them as e.g. {@code reviews_processed_total}, {@code review_duration_seconds},
 * {@code review_findings_count}.
 * <p>
 * Tags are always from a small fixed set (outcome, status, reason) — never a raw
 * repo name or PR number, which would explode the time-series cardinality.
 */
@Component
public class ReviewMetrics {

    private final MeterRegistry registry;

    private final Map<ReviewOutcome, Counter> processed = new EnumMap<>(ReviewOutcome.class);
    private final Timer reviewDuration;
    private final Timer llmCallDuration;
    private final Counter llmCallErrors;
    private final DistributionSummary findingsPerReview;

    public ReviewMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Pre-register the fixed meters so they appear on /actuator/prometheus
        // even before the first event.
        for (ReviewOutcome outcome : ReviewOutcome.values()) {
            processed.put(outcome, Counter.builder("reviews.processed")
                    .description("Review jobs by terminal outcome")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
        this.reviewDuration = Timer.builder("review.duration")
                .description("End-to-end review time: job received -> posted to GitHub")
                .register(registry);
        this.llmCallDuration = Timer.builder("llm.call.duration")
                .description("Per-file LLM call duration")
                .register(registry);
        this.llmCallErrors = Counter.builder("llm.call.errors")
                .description("LLM calls that failed")
                .register(registry);
        this.findingsPerReview = DistributionSummary.builder("review.findings")
                .description("Findings kept per review")
                .register(registry);
    }

    public void recordProcessed(ReviewOutcome outcome) {
        processed.get(outcome).increment();
    }

    public void recordFindings(int count) {
        findingsPerReview.record(count);
    }

    /** Start a timing sample; stop it with {@link #stopReview} or {@link #stopLlmCall}. */
    public Timer.Sample startSample() {
        return Timer.start(registry);
    }

    public void stopReview(Timer.Sample sample) {
        sample.stop(reviewDuration);
    }

    public void stopLlmCall(Timer.Sample sample) {
        sample.stop(llmCallDuration);
    }

    public void recordLlmError() {
        llmCallErrors.increment();
    }

    /** github.api.errors tagged by HTTP status (or "io" for connect/read failures). */
    public void recordGithubApiError(String status) {
        registry.counter("github.api.errors", "status", status).increment();
    }

    /** jobs.dlq.total tagged by reason (permanent / retries_exhausted). */
    public void recordDeadLetter(String reason) {
        registry.counter("jobs.dlq", "reason", reason).increment();
    }
}
