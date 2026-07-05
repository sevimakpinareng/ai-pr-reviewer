package com.example.prreviewer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.example.prreviewer.dto.ReviewContext;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.metrics.ReviewMetrics;
import com.example.prreviewer.metrics.ReviewOutcome;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;

import io.micrometer.core.instrument.Timer;

/**
 * Orchestrates one PR review end to end: persist a PROCESSING review, run the
 * (slow) analysis outside any transaction, persist the result as DONE (or
 * FAILED on error), then post the review to GitHub as a separate best-effort
 * step. Deliberately NOT {@code @Transactional}: it calls several short
 * transactions on {@link ReviewPersistenceService} with the slow LLM and GitHub
 * calls in between, never inside a transaction.
 */
@Service
public class ReviewProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ReviewProcessingService.class);

    private final ReviewPersistenceService persistence;
    private final ReviewService reviewService;
    private final GithubReviewPublisher reviewPublisher;
    private final ReviewMetrics metrics;

    public ReviewProcessingService(ReviewPersistenceService persistence,
                                   ReviewService reviewService,
                                   GithubReviewPublisher reviewPublisher,
                                   ReviewMetrics metrics) {
        this.persistence = persistence;
        this.reviewService = reviewService;
        this.reviewPublisher = reviewPublisher;
        this.metrics = metrics;
    }

    public void processPullRequest(PullRequestInfo info, String rawDiff) {
        int prNumber = (int) info.prNumber();
        // Timer starts at processing entry (right after the job was received) and
        // stops after the review has been posted to GitHub.
        Timer.Sample sample = metrics.startSample();

        Long repositoryId = persistence.upsertRepository(
                info.installationId(), info.accountLogin(), info.repoFullName(), info.defaultBranch());
        Long reviewId = persistence.startReview(repositoryId, prNumber, info.headSha());
        MDC.put("reviewId", String.valueOf(reviewId)); // cleared by the consumer's finally
        log.info("Review {} started (PROCESSING) for {} #{} @ {}",
                reviewId, info.repoFullName(), prNumber, info.headSha());

        ReviewResult result;
        try {
            result = reviewService.review(
                    rawDiff, new ReviewContext(info.repoFullName(), prNumber, info.headSha()));
            persistence.completeReview(reviewId, result);
            log.info("Review {} DONE for {} #{}: {} finding(s) — {}",
                    reviewId, info.repoFullName(), prNumber, result.findings().size(), result.summary());
        } catch (Exception e) {
            log.error("Review {} FAILED for {} #{}: {}",
                    reviewId, info.repoFullName(), prNumber, e.getMessage(), e);
            persistence.failReview(reviewId);
            metrics.recordProcessed(ReviewOutcome.FAILED);
            return;
        }

        metrics.recordFindings(result.findings().size());
        // Posting is best-effort: a failure here leaves the review DONE in our DB.
        publishToGithub(info, prNumber, reviewId, result, rawDiff);
        metrics.recordProcessed(ReviewOutcome.DONE);
        metrics.stopReview(sample);
    }

    private void publishToGithub(PullRequestInfo info, int prNumber, Long reviewId,
                                 ReviewResult result, String rawDiff) {
        try {
            Long githubReviewId = reviewPublisher.publish(
                    info.installationId(), info.owner(), info.repo(), prNumber,
                    info.headSha(), result, rawDiff);
            if (githubReviewId != null) {
                persistence.recordGithubReviewId(reviewId, githubReviewId);
                log.info("Review {} posted to GitHub as review {}", reviewId, githubReviewId);
            }
        } catch (Exception e) {
            log.error("Review {} is DONE but posting to GitHub failed for {} #{}: {}",
                    reviewId, info.repoFullName(), prNumber, e.getMessage(), e);
        }
    }
}
