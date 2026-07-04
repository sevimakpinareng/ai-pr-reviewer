package com.example.prreviewer.dto;

/**
 * The unit of work published when a reviewable pull_request webhook arrives.
 * <p>
 * Intentionally small: it carries only what's needed to locate the PR. The diff
 * is deliberately NOT included — the consumer fetches it fresh, so messages stay
 * tiny and never carry stale or huge payloads.
 *
 * @param deliveryId the webhook's {@code X-GitHub-Delivery} id, for correlation
 */
public record ReviewJobMessage(long installationId, String owner, String repo, int prNumber,
                               String commitSha, String action, String deliveryId) {
}
