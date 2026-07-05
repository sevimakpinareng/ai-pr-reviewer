package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;
import com.example.prreviewer.metrics.ReviewMetrics;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReviewProcessingServiceTest {

    private ReviewPersistenceService persistence;
    private ReviewService reviewService;
    private GithubReviewPublisher publisher;
    private SimpleMeterRegistry meterRegistry;
    private ReviewProcessingService processing;

    private static final PullRequestInfo PR = new PullRequestInfo(
            "octocat/Hello-World", 42, "opened", "sha123", 555L, "octocat", "main");

    @BeforeEach
    void setUp() {
        persistence = mock(ReviewPersistenceService.class);
        reviewService = mock(ReviewService.class);
        publisher = mock(GithubReviewPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        processing = new ReviewProcessingService(
                persistence, reviewService, publisher, new ReviewMetrics(meterRegistry));

        when(persistence.upsertRepository(anyLong(), any(), anyString(), any())).thenReturn(10L);
        when(persistence.startReview(anyLong(), anyInt(), anyString())).thenReturn(99L);
    }

    private double processedCount(String outcome) {
        return meterRegistry.get("reviews.processed").tag("outcome", outcome).counter().count();
    }

    @Test
    void happyPathCompletesPublishesAndRecordsId() {
        ReviewResult result = new ReviewResult("s", List.of(
                new ReviewFinding("Foo.java", 2, Severity.ERROR, "t", "d")));
        when(reviewService.review(any(), any())).thenReturn(result);
        when(publisher.publish(anyLong(), any(), any(), anyInt(), any(), any(), any())).thenReturn(777L);

        processing.processPullRequest(PR, "diff");

        verify(persistence).completeReview(99L, result);
        verify(persistence).recordGithubReviewId(99L, 777L);
        verify(persistence, never()).failReview(anyLong());
        assertEquals(1.0, processedCount("done"));
    }

    @Test
    void analysisFailureMarksFailedAndSkipsPublish() {
        when(reviewService.review(any(), any())).thenThrow(new ReviewFailedException("all failed"));

        processing.processPullRequest(PR, "diff");

        verify(persistence).failReview(99L);
        verify(persistence, never()).completeReview(anyLong(), any());
        verify(publisher, never()).publish(anyLong(), any(), any(), anyInt(), any(), any(), any());
        assertEquals(1.0, processedCount("failed"));
    }

    @Test
    void publishFailureLeavesReviewDone() {
        ReviewResult result = new ReviewResult("s", List.of());
        when(reviewService.review(any(), any())).thenReturn(result);
        when(publisher.publish(anyLong(), any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new RuntimeException("GitHub 422"));

        // Must not throw.
        processing.processPullRequest(PR, "diff");

        verify(persistence).completeReview(99L, result);
        verify(persistence, never()).failReview(anyLong());
        verify(persistence, never()).recordGithubReviewId(anyLong(), any());
    }

    @Test
    void nullReviewIdIsNotRecorded() {
        when(reviewService.review(any(), any())).thenReturn(new ReviewResult("s", List.of()));
        when(publisher.publish(anyLong(), any(), any(), anyInt(), any(), any(), any())).thenReturn(null);

        processing.processPullRequest(PR, "diff");

        verify(persistence, never()).recordGithubReviewId(anyLong(), any());
    }

    private static <T> T mock(Class<T> type) {
        return Mockito.mock(type);
    }
}
