package com.example.prreviewer.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReviewMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReviewMetrics metrics = new ReviewMetrics(registry);

    @Test
    void recordsProcessedByOutcome() {
        metrics.recordProcessed(ReviewOutcome.DONE);
        metrics.recordProcessed(ReviewOutcome.DONE);
        metrics.recordProcessed(ReviewOutcome.SKIPPED_DUP);

        assertEquals(2.0, registry.get("reviews.processed").tag("outcome", "done").counter().count());
        assertEquals(1.0, registry.get("reviews.processed").tag("outcome", "skipped_dup").counter().count());
    }

    @Test
    void recordsFindingsDistribution() {
        metrics.recordFindings(3);
        metrics.recordFindings(1);

        assertEquals(2, registry.get("review.findings").summary().count());
        assertEquals(4.0, registry.get("review.findings").summary().totalAmount());
    }

    @Test
    void recordsGithubApiErrorTaggedByStatus() {
        metrics.recordGithubApiError("404");
        assertEquals(1.0, registry.get("github.api.errors").tag("status", "404").counter().count());
    }

    @Test
    void recordsLlmErrorsAndDeadLetters() {
        metrics.recordLlmError();
        assertEquals(1.0, registry.get("llm.call.errors").counter().count());

        metrics.recordDeadLetter("permanent");
        assertEquals(1.0, registry.get("jobs.dlq").tag("reason", "permanent").counter().count());
    }

    @Test
    void recordsTimers() {
        Timer.Sample review = metrics.startSample();
        metrics.stopReview(review);
        Timer.Sample llm = metrics.startSample();
        metrics.stopLlmCall(llm);

        assertEquals(1, registry.get("review.duration").timer().count());
        assertEquals(1, registry.get("llm.call.duration").timer().count());
    }
}
