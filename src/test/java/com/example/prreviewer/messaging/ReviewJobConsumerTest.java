package com.example.prreviewer.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import com.example.prreviewer.dto.ReviewJobMessage;
import com.example.prreviewer.metrics.ReviewMetrics;
import com.example.prreviewer.service.GithubApiClient;
import com.example.prreviewer.service.PrSupersedingService;
import com.example.prreviewer.service.ReviewIdempotencyService;
import com.example.prreviewer.service.ReviewProcessingService;
import com.example.prreviewer.service.ReviewRateLimiter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReviewJobConsumerTest {

    private static final ReviewJobMessage JOB =
            new ReviewJobMessage(555L, "octocat", "Hello-World", 42, "sha123", "opened", "delivery-1");
    private static final String DIFF = "diff --git a/Foo.java b/Foo.java\n+x";

    private GithubApiClient githubApiClient;
    private ReviewProcessingService processing;
    private ReviewIdempotencyService idempotency;
    private PrSupersedingService superseding;
    private ReviewRateLimiter rateLimiter;
    private SimpleMeterRegistry meterRegistry;
    private ReviewJobConsumer consumer;

    @BeforeEach
    void setUp() {
        githubApiClient = mock(GithubApiClient.class);
        processing = mock(ReviewProcessingService.class);
        idempotency = mock(ReviewIdempotencyService.class);
        superseding = mock(PrSupersedingService.class);
        rateLimiter = mock(ReviewRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        consumer = new ReviewJobConsumer(githubApiClient, processing, idempotency, superseding,
                rateLimiter, new ReviewMetrics(meterRegistry), Duration.ofMillis(1));

        // Default: all guards pass; individual tests override as needed.
        when(idempotency.isAlreadyProcessed(anyString())).thenReturn(false);
        when(superseding.isStale(anyString(), anyString(), anyInt(), anyString())).thenReturn(false);
        when(rateLimiter.tryAcquire(anyLong())).thenReturn(true);
    }

    private double processedCount(String outcome) {
        return meterRegistry.get("reviews.processed").tag("outcome", outcome).counter().count();
    }

    @Test
    void skipsDuplicateDelivery() {
        when(idempotency.isAlreadyProcessed("delivery-1")).thenReturn(true);

        consumer.onReviewJob(JOB);

        verify(githubApiClient, never()).fetchPullRequestDiff(anyLong(), anyString(), anyString(), anyInt());
        verify(processing, never()).processPullRequest(any(), any());
        verify(idempotency, never()).markProcessed(anyString());
        assertEquals(1.0, processedCount("skipped_dup"));
    }

    @Test
    void skipsStaleCommit() {
        when(superseding.isStale("octocat", "Hello-World", 42, "sha123")).thenReturn(true);

        consumer.onReviewJob(JOB);

        verify(processing, never()).processPullRequest(any(), any());
        verify(idempotency, never()).markProcessed(anyString());
        assertEquals(1.0, processedCount("skipped_stale"));
    }

    @Test
    void requeuesWhenRateLimited() {
        when(rateLimiter.tryAcquire(555L)).thenReturn(false);

        assertThrows(ImmediateRequeueAmqpException.class, () -> consumer.onReviewJob(JOB));

        verify(processing, never()).processPullRequest(any(), any());
        verify(idempotency, never()).markProcessed(anyString());
        assertEquals(1.0, processedCount("rate_limited"));
    }

    @Test
    void setsMdcDuringProcessingAndClearsItAfter() {
        when(githubApiClient.fetchPullRequestDiff(anyLong(), anyString(), anyString(), anyInt())).thenReturn(DIFF);
        AtomicReference<String> deliveryDuringProcessing = new AtomicReference<>();
        doAnswer(inv -> {
            deliveryDuringProcessing.set(MDC.get("deliveryId"));
            return null;
        }).when(processing).processPullRequest(any(), any());

        consumer.onReviewJob(JOB);

        assertEquals("delivery-1", deliveryDuringProcessing.get(), "MDC set while processing");
        assertNull(MDC.get("deliveryId"), "MDC cleared in finally");
        assertNull(MDC.get("reviewId"));
    }

    @Test
    void processesAndMarksOnSuccess() {
        when(githubApiClient.fetchPullRequestDiff(555L, "octocat", "Hello-World", 42)).thenReturn(DIFF);

        consumer.onReviewJob(JOB);

        verify(processing).processPullRequest(any(), eq(DIFF));
        verify(idempotency).markProcessed("delivery-1");
    }

    @Test
    void doesNotMarkOnTransientFailure() {
        when(githubApiClient.fetchPullRequestDiff(anyLong(), anyString(), anyString(), anyInt())).thenReturn(DIFF);
        doThrowRuntime();

        assertThrows(RuntimeException.class, () -> consumer.onReviewJob(JOB));

        // Not marked -> a retry can reprocess this delivery.
        verify(idempotency, never()).markProcessed(anyString());
    }

    @Test
    void permanentErrorGoesToDlqAndIsNotMarked() {
        when(githubApiClient.fetchPullRequestDiff(anyLong(), anyString(), anyString(), anyInt()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onReviewJob(JOB));

        verify(idempotency, never()).markProcessed(anyString());
    }

    @Test
    void emptyDiffIsMarkedProcessed() {
        when(githubApiClient.fetchPullRequestDiff(anyLong(), anyString(), anyString(), anyInt())).thenReturn("  ");

        consumer.onReviewJob(JOB);

        verify(processing, never()).processPullRequest(any(), any());
        verify(idempotency).markProcessed("delivery-1");
    }

    private void doThrowRuntime() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(processing).processPullRequest(any(), any());
    }
}
