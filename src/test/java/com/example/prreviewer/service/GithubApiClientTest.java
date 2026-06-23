package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubApiClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void fetchesRawDiffWithTokenAndDiffAcceptHeader() throws Exception {
        String diffBody = """
                diff --git a/README.md b/README.md
                index 0000001..0000002 100644
                --- a/README.md
                +++ b/README.md
                @@ -1 +1 @@
                -old
                +new
                """;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/vnd.github.v3.diff")
                .setBody(diffBody));

        GithubAppAuthService authService = mock(GithubAppAuthService.class);
        when(authService.getInstallationToken(12345678L)).thenReturn("ghs_installationtoken");

        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        GithubApiClient client = new GithubApiClient(restClient, authService);

        String diff = client.fetchPullRequestDiff(12345678L, "octocat", "Hello-World", 42);

        assertEquals(diffBody, diff);

        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/repos/octocat/Hello-World/pulls/42", request.getPath());
        assertEquals("Bearer ghs_installationtoken", request.getHeader("Authorization"));
        assertTrue(request.getHeader("Accept").contains("application/vnd.github.v3.diff"));
    }
}
