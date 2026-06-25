package com.example.prreviewer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.prreviewer.dto.ReviewContext;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;

/**
 * Orchestrates one PR review end to end: persist a PROCESSING review, run the
 * (slow) analysis outside any transaction, then persist the result as DONE — or
 * FAILED on error. Deliberately NOT {@code @Transactional}: it calls several
 * short transactions on {@link ReviewPersistenceService} with the LLM call in
 * between. Writes nothing to GitHub yet.
 */
@Service
public class ReviewProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ReviewProcessingService.class);

    private final ReviewPersistenceService persistence;
    private final ReviewService reviewService;

    public ReviewProcessingService(ReviewPersistenceService persistence, ReviewService reviewService) {
        this.persistence = persistence;
        this.reviewService = reviewService;
    }

    public void processPullRequest(PullRequestInfo info, String rawDiff) {
        int prNumber = (int) info.prNumber();
        Long repositoryId = persistence.upsertRepository(
                info.installationId(), info.accountLogin(), info.repoFullName(), info.defaultBranch());
        Long reviewId = persistence.startReview(repositoryId, prNumber, info.headSha());
        log.info("Review {} started (PROCESSING) for {} #{} @ {}",
                reviewId, info.repoFullName(), prNumber, info.headSha());

        try {
            ReviewResult result = reviewService.review(
                    rawDiff, new ReviewContext(info.repoFullName(), prNumber, info.headSha()));
            persistence.completeReview(reviewId, result);
            log.info("Review {} DONE for {} #{}: {} finding(s) — {}",
                    reviewId, info.repoFullName(), prNumber, result.findings().size(), result.summary());
        } catch (Exception e) {
            log.error("Review {} FAILED for {} #{}: {}",
                    reviewId, info.repoFullName(), prNumber, e.getMessage(), e);
            persistence.failReview(reviewId);
        }
    }
}
