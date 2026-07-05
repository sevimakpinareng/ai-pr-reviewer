package com.example.prreviewer.messaging;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.dto.ReviewJobMessage;
import com.example.prreviewer.service.GithubApiClient;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;
import com.example.prreviewer.service.PrSupersedingService;
import com.example.prreviewer.service.ReviewIdempotencyService;
import com.example.prreviewer.service.ReviewProcessingService;
import com.example.prreviewer.service.ReviewRateLimiter;

/**
 * Consumes review jobs and runs the heavy chain: fetch the diff fresh (H3),
 * analyze + persist (H5), post the review to GitHub (H6).
 * <p>
 * Before doing any work it applies three Redis guards:
 * <ol>
 *   <li>dedupe — skip a delivery already processed to completion;</li>
 *   <li>superseding — skip a job whose commit a newer push has replaced;</li>
 *   <li>rate limit — requeue (don't drop) a job that exceeds the per-installation
 *       window, so it's retried later instead of lost.</li>
 * </ol>
 * The dedupe mark is written only on success, so transient failures still retry.
 * Retry/DLQ policy is on the listener container (see H7).
 */
@Component
public class ReviewJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobConsumer.class);

    private final GithubApiClient githubApiClient;
    private final ReviewProcessingService reviewProcessingService;
    private final ReviewIdempotencyService idempotencyService;
    private final PrSupersedingService supersedingService;
    private final ReviewRateLimiter rateLimiter;
    private final Duration rateLimitRequeueDelay;

    public ReviewJobConsumer(GithubApiClient githubApiClient,
                             ReviewProcessingService reviewProcessingService,
                             ReviewIdempotencyService idempotencyService,
                             PrSupersedingService supersedingService,
                             ReviewRateLimiter rateLimiter,
                             @Value("${app.ratelimit.requeue-delay:500ms}") Duration rateLimitRequeueDelay) {
        this.githubApiClient = githubApiClient;
        this.reviewProcessingService = reviewProcessingService;
        this.idempotencyService = idempotencyService;
        this.supersedingService = supersedingService;
        this.rateLimiter = rateLimiter;
        this.rateLimitRequeueDelay = rateLimitRequeueDelay;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onReviewJob(ReviewJobMessage job) {
        log.info("Review job received: delivery={} repo={}/{} pr=#{} action={} commit={}",
                job.deliveryId(), job.owner(), job.repo(), job.prNumber(), job.action(), job.commitSha());

        // 1. Dedupe: a delivery we already finished must not run again (ack, not an error).
        if (idempotencyService.isAlreadyProcessed(job.deliveryId())) {
            log.info("Duplicate delivery already processed; skipping: delivery={} repo={}/{} pr=#{}",
                    job.deliveryId(), job.owner(), job.repo(), job.prNumber());
            return;
        }

        // 2. Superseding: a newer commit for this PR makes this job stale (ack, not an error).
        if (supersedingService.isStale(job.owner(), job.repo(), job.prNumber(), job.commitSha())) {
            log.info("Job superseded by a newer commit; skipping: delivery={} repo={}/{} pr=#{} commit={}",
                    job.deliveryId(), job.owner(), job.repo(), job.prNumber(), job.commitSha());
            return;
        }

        // 3. Rate limit: over budget -> requeue (deferred), don't drop the work.
        if (!rateLimiter.tryAcquire(job.installationId())) {
            log.info("Rate limit hit for installation {}; requeueing: delivery={} repo={}/{} pr=#{}",
                    job.installationId(), job.deliveryId(), job.owner(), job.repo(), job.prNumber());
            sleepQuietly(rateLimitRequeueDelay);
            throw new ImmediateRequeueAmqpException(
                    "Rate limit exceeded for installation " + job.installationId());
        }

        try {
            String diff = githubApiClient.fetchPullRequestDiff(
                    job.installationId(), job.owner(), job.repo(), job.prNumber());
            if (diff == null || diff.isBlank()) {
                log.info("Empty diff; nothing to review: delivery={} repo={}/{} pr=#{}",
                        job.deliveryId(), job.owner(), job.repo(), job.prNumber());
                idempotencyService.markProcessed(job.deliveryId());
                return;
            }

            PullRequestInfo info = new PullRequestInfo(
                    job.owner() + "/" + job.repo(), job.prNumber(), job.action(), job.commitSha(),
                    job.installationId(), job.owner(), null);
            reviewProcessingService.processPullRequest(info, diff);

            // Mark done only after success, so a transient failure above can retry.
            idempotencyService.markProcessed(job.deliveryId());
            log.info("Review job processed: delivery={} repo={}/{} pr=#{}",
                    job.deliveryId(), job.owner(), job.repo(), job.prNumber());
        } catch (HttpClientErrorException e) {
            // 4xx -> permanent. Retrying won't help; send straight to the DLQ.
            log.error("Permanent error, routing to DLQ: delivery={} repo={}/{} pr=#{} status={}",
                    job.deliveryId(), job.owner(), job.repo(), job.prNumber(), e.getStatusCode());
            throw new AmqpRejectAndDontRequeueException("Permanent GitHub error " + e.getStatusCode(), e);
        } catch (Exception e) {
            // Transient (network, 5xx, ...) -> let the listener retry; the DLQ
            // catches it once retries are exhausted.
            log.warn("Transient error, will retry: delivery={} repo={}/{} pr=#{}: {}",
                    job.deliveryId(), job.owner(), job.repo(), job.prNumber(), e.getMessage());
            throw e;
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
