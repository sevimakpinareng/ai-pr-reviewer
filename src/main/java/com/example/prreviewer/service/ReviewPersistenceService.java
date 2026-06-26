package com.example.prreviewer.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.entity.Installation;
import com.example.prreviewer.entity.Repository;
import com.example.prreviewer.entity.Review;
import com.example.prreviewer.entity.ReviewComment;
import com.example.prreviewer.entity.ReviewStatus;
import com.example.prreviewer.repository.InstallationRepository;
import com.example.prreviewer.repository.RepositoryRepository;
import com.example.prreviewer.repository.ReviewRepository;

/**
 * Owns all DB writes for the review flow. Each public method is its own short
 * transaction; crucially, the slow LLM call happens between these calls, NOT
 * inside one, so no DB transaction/connection is held open during model I/O.
 */
@Service
public class ReviewPersistenceService {

    private final InstallationRepository installationRepository;
    private final RepositoryRepository repositoryRepository;
    private final ReviewRepository reviewRepository;

    public ReviewPersistenceService(InstallationRepository installationRepository,
                                    RepositoryRepository repositoryRepository,
                                    ReviewRepository reviewRepository) {
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Idempotently finds-or-creates the installation and repository. Calling it
     * again with the same identifiers returns the existing rows.
     *
     * @return the repository's id
     */
    @Transactional
    public Long upsertRepository(long githubInstallationId, String accountLogin,
                                 String fullName, String defaultBranch) {
        Installation installation = installationRepository
                .findByGithubInstallationId(githubInstallationId)
                .orElseGet(() -> installationRepository.save(
                        new Installation(githubInstallationId, accountLogin)));

        Repository repository = repositoryRepository
                .findByFullName(fullName)
                .orElseGet(() -> repositoryRepository.save(
                        new Repository(installation, fullName, defaultBranch)));

        return repository.getId();
    }

    /** Creates the review row in PROCESSING state and returns its id. */
    @Transactional
    public Long startReview(Long repositoryId, int prNumber, String commitSha) {
        Repository repository = repositoryRepository.getReferenceById(repositoryId);
        Review review = new Review(repository, prNumber, commitSha, ReviewStatus.PROCESSING);
        return reviewRepository.save(review).getId();
    }

    /** Writes findings + summary and marks the review DONE. */
    @Transactional
    public void completeReview(Long reviewId, ReviewResult result) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review not found: " + reviewId));

        review.setSummary(result.summary());
        for (ReviewFinding finding : result.findings()) {
            review.addComment(new ReviewComment(
                    finding.filePath(),
                    finding.line(),
                    finding.severity(),
                    finding.title(),
                    finding.detail() == null ? "" : finding.detail()));
        }
        review.setStatus(ReviewStatus.DONE);
        review.setFinishedAt(Instant.now());
    }

    /** Marks the review FAILED. */
    @Transactional
    public void failReview(Long reviewId) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(ReviewStatus.FAILED);
            review.setFinishedAt(Instant.now());
        });
    }

    /** Records the GitHub review id once the review has been posted. */
    @Transactional
    public void recordGithubReviewId(Long reviewId, Long githubReviewId) {
        reviewRepository.findById(reviewId)
                .ifPresent(review -> review.setGithubReviewId(githubReviewId));
    }
}
