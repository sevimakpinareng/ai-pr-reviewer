package com.example.prreviewer.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Thin client over the GitHub REST API. Every call is authenticated with a
 * (cached) installation token obtained from {@link GithubAppAuthService}.
 */
@Service
public class GithubApiClient {

    /** Media type that makes GitHub return a raw unified diff. */
    private static final MediaType DIFF_MEDIA_TYPE = MediaType.valueOf("application/vnd.github.v3.diff");

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
}
