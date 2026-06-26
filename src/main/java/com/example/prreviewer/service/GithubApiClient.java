package com.example.prreviewer.service;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thin client over the GitHub REST API. Every call is authenticated with a
 * (cached) installation token obtained from {@link GithubAppAuthService}.
 */
@Service
public class GithubApiClient {

    /** Media type that makes GitHub return a raw unified diff. */
    private static final MediaType DIFF_MEDIA_TYPE = MediaType.valueOf("application/vnd.github.v3.diff");
    private static final MediaType GITHUB_JSON = MediaType.valueOf("application/vnd.github+json");

    private final RestClient githubRestClient;
    private final GithubAppAuthService authService;

    public GithubApiClient(RestClient githubRestClient, GithubAppAuthService authService) {
        this.githubRestClient = githubRestClient;
        this.authService = authService;
    }

    /**
     * Fetches the unified diff of a pull request as raw text.
     *
     * @return the diff body (may be large)
     */
    public String fetchPullRequestDiff(long installationId, String owner, String repo, int pullNumber) {
        String token = authService.getInstallationToken(installationId);

        return githubRestClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, pullNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(DIFF_MEDIA_TYPE)
                .retrieve()
                .body(String.class);
    }

    /**
     * Posts a single batched pull-request review. The {@code event} is always
     * {@code COMMENT} — a bot must neither approve nor block a merge.
     *
     * @param commitId the commit the review applies to (the one we analyzed)
     * @param body     the overall review body (summary + non-inline findings)
     * @param comments inline comments, each pinned to a RIGHT-side diff line
     * @return the GitHub review id
     */
    public Long submitReview(long installationId, String owner, String repo, int pullNumber,
                             String commitId, String body, List<InlineComment> comments) {
        String token = authService.getInstallationToken(installationId);
        ReviewRequest payload = new ReviewRequest(commitId, body, "COMMENT", comments);

        ReviewResponse response = githubRestClient.post()
                .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", owner, repo, pullNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(GITHUB_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(ReviewResponse.class);

        return response == null ? null : response.id();
    }

    /** One inline review comment, serialized to GitHub's review-comment shape. */
    public record InlineComment(String path, int line, String side, String body) {
    }

    private record ReviewRequest(@JsonProperty("commit_id") String commitId, String body, String event,
                                 List<InlineComment> comments) {
    }

    private record ReviewResponse(Long id) {
    }
}
