package com.example.prreviewer.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;
import com.example.prreviewer.entity.Review;
import com.example.prreviewer.entity.ReviewStatus;
import com.example.prreviewer.repository.InstallationRepository;
import com.example.prreviewer.repository.RepositoryRepository;
import com.example.prreviewer.repository.ReviewCommentRepository;
import com.example.prreviewer.repository.ReviewRepository;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;
import com.example.prreviewer.service.ReviewFailedException;
import com.example.prreviewer.service.ReviewPersistenceService;
import com.example.prreviewer.service.ReviewProcessingService;
import com.example.prreviewer.service.ReviewService;

/**
 * Exercises the real persistence flow against a real PostgreSQL (Testcontainers).
 * Booting the full context also validates the JPA entities against the
 * Flyway-managed schema (ddl-auto=validate).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ReviewPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    // Replace the LLM-backed service so we control success/failure deterministically.
    @MockitoBean
    ReviewService reviewService;

    @Autowired
    ReviewPersistenceService persistence;
    @Autowired
    ReviewProcessingService processing;
    @Autowired
    ReviewRepository reviewRepository;
    @Autowired
    ReviewCommentRepository reviewCommentRepository;
    @Autowired
    RepositoryRepository repositoryRepository;
    @Autowired
    InstallationRepository installationRepository;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        repositoryRepository.deleteAll();
        installationRepository.deleteAll();
    }

    @Test
    void upsertRepositoryIsIdempotent() {
        Long firstRepoId = persistence.upsertRepository(555L, "octocat", "octocat/Hello-World", "main");
        Long secondRepoId = persistence.upsertRepository(555L, "octocat", "octocat/Hello-World", "main");

        assertEquals(firstRepoId, secondRepoId);
        assertEquals(1, repositoryRepository.count());
        assertEquals(1, installationRepository.count());
    }

    @Test
    void startThenCompleteMovesProcessingToDoneWithComments() {
        Long repoId = persistence.upsertRepository(555L, "octocat", "octocat/Hello-World", "main");
        Long reviewId = persistence.startReview(repoId, 42, "deadbeef");

        assertEquals(ReviewStatus.PROCESSING, reviewRepository.findById(reviewId).orElseThrow().getStatus());

        persistence.completeReview(reviewId, new ReviewResult("Looks risky.", List.of(
                new ReviewFinding("Foo.java", 10, Severity.ERROR, "NPE", "possible null deref"),
                new ReviewFinding("Bar.java", null, Severity.WARNING, "leak", "stream not closed"))));

        Review review = reviewRepository.findById(reviewId).orElseThrow();
        assertEquals(ReviewStatus.DONE, review.getStatus());
        assertEquals("Looks risky.", review.getSummary());
        assertNotNull(review.getFinishedAt());
        assertEquals(2, reviewCommentRepository.findByReviewId(reviewId).size());
    }

    @Test
    void failReviewMarksFailed() {
        Long repoId = persistence.upsertRepository(555L, "octocat", "octocat/Hello-World", "main");
        Long reviewId = persistence.startReview(repoId, 42, "deadbeef");

        persistence.failReview(reviewId);

        Review review = reviewRepository.findById(reviewId).orElseThrow();
        assertEquals(ReviewStatus.FAILED, review.getStatus());
        assertNotNull(review.getFinishedAt());
    }

    @Test
    void processPullRequestPersistsDoneReview() {
        when(reviewService.review(any(), any())).thenReturn(new ReviewResult(
                "One issue.", List.of(new ReviewFinding("Foo.java", 3, Severity.WARNING, "t", "d"))));

        processing.processPullRequest(samplePr(), "diff --git a/Foo.java b/Foo.java\n+x");

        Review review = reviewRepository.findAll().get(0);
        assertEquals(ReviewStatus.DONE, review.getStatus());
        assertEquals(1, reviewCommentRepository.findByReviewId(review.getId()).size());
    }

    @Test
    void processPullRequestMarksFailedWhenReviewThrows() {
        when(reviewService.review(any(), any())).thenThrow(new ReviewFailedException("all files failed"));

        processing.processPullRequest(samplePr(), "diff --git a/Foo.java b/Foo.java\n+x");

        Review review = reviewRepository.findAll().get(0);
        assertEquals(ReviewStatus.FAILED, review.getStatus());
        assertNotNull(review.getFinishedAt());
        assertNull(review.getSummary());
        assertTrue(reviewCommentRepository.findByReviewId(review.getId()).isEmpty());
    }

    private static PullRequestInfo samplePr() {
        return new PullRequestInfo("octocat/Hello-World", 42, "opened", "deadbeef",
                555L, "octocat", "main");
    }
}
