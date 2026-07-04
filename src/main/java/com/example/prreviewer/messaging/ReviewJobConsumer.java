package com.example.prreviewer.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.dto.ReviewJobMessage;
import com.example.prreviewer.service.GithubApiClient;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;
import com.example.prreviewer.service.ReviewProcessingService;

/**
 * Consumes review jobs and runs the heavy chain: fetch the diff fresh (H3),
 * analyze + persist (H5), post the review to GitHub (H6).
 * <p>
 * Retry policy is configured on the listener container
 * ({@code spring.rabbitmq.listener.simple.retry.*}): transient failures are
 * retried with exponential backoff, then dead-lettered. Permanent failures
 * (4xx from GitHub — PR gone, no access, unprocessable) skip retries and are
 * dead-lettered immediately via {@link AmqpRejectAndDontRequeueException}.
 */
@Component
public class ReviewJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobConsumer.class);

    private final GithubApiClient githubApiClient;
    private final ReviewProcessingService reviewProcessingService;

    public ReviewJobConsumer(GithubApiClient githubApiClient,
                             ReviewProcessingService reviewProcessingService) {
        this.githubApiClient = githubApiClient;
        this.reviewProcessingService = reviewProcessingService;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onReviewJob(ReviewJobMessage job) {
        log.info("Review job received: delivery={} repo={}/{} pr=#{} action={}",
                job.deliveryId(), job.owner(), job.repo(), job.prNumber(), job.action());
        try {
            String diff = githubApiClient.fetchPullRequestDiff(
                    job.installationId(), job.owner(), job.repo(), job.prNumber());
            if (diff == null || diff.isBlank()) {
                log.info("Empty diff; nothing to review: delivery={} repo={}/{} pr=#{}",
                        job.deliveryId(), job.owner(), job.repo(), job.prNumber());
                return;
            }

            PullRequestInfo info = new PullRequestInfo(
                    job.owner() + "/" + job.repo(), job.prNumber(), job.action(), job.commitSha(),
                    job.installationId(), job.owner(), null);
            reviewProcessingService.processPullRequest(info, diff);

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
}
