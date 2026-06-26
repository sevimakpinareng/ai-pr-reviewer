package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class GithubReviewPublisherTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Foo.java commentable RIGHT lines: 1 (ctx), 2 (added), 3 (ctx).
    private static final String DIFF = """
            diff --git a/Foo.java b/Foo.java
            index 1111111..2222222 100644
            --- a/Foo.java
            +++ b/Foo.java
            @@ -1,2 +1,3 @@
             ctx1
            +added2
             ctx3
            """;

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
    void buildsBatchedReviewWithInlineAndSummaryFindings() throws Exception {
        server.enqueue(json("{\"id\": 4242}"));
        GithubReviewPublisher publisher = publisher(true);

        ReviewResult result = new ReviewResult("Overall looks ok.", List.of(
                new ReviewFinding("Foo.java", 2, Severity.ERROR, "Bug here", "fix it"),
                new ReviewFinding("Foo.java", 999, Severity.WARNING, "Off diff", "not in diff"),
                new ReviewFinding("Foo.java", null, Severity.INFO, "General", "no line")));

        Long reviewId = publisher.publish(7L, "octocat", "Hello-World", 7, "sha123", result, DIFF);

        assertEquals(4242L, reviewId);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/repos/octocat/Hello-World/pulls/7/reviews", request.getPath());

        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertEquals("sha123", body.get("commit_id").asText());
        assertEquals("COMMENT", body.get("event").asText());

        // Only the in-diff finding is inline.
        assertEquals(1, body.get("comments").size());
        JsonNode inline = body.get("comments").get(0);
        assertEquals("Foo.java", inline.get("path").asText());
        assertEquals(2, inline.get("line").asInt());
        assertEquals("RIGHT", inline.get("side").asText());
        assertTrue(inline.get("body").asText().contains("[ERROR]"));

        // The off-diff and null-line findings fall into the body.
        String reviewBody = body.get("body").asText();
        assertTrue(reviewBody.contains("Off diff"));
        assertTrue(reviewBody.contains("General"));
    }

    @Test
    void postsMinimalReviewWhenEmptyAndFlagOn() throws Exception {
        server.enqueue(json("{\"id\": 1}"));
        GithubReviewPublisher publisher = publisher(true);

        Long reviewId = publisher.publish(7L, "o", "r", 7, "sha", ReviewResult.empty("x"), DIFF);

        assertEquals(1L, reviewId);
        RecordedRequest request = server.takeRequest();
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertEquals("Önemli bir sorun bulunamadı.", body.get("body").asText());
        assertEquals(0, body.get("comments").size());
    }

    @Test
    void postsNothingWhenEmptyAndFlagOff() {
        GithubReviewPublisher publisher = publisher(false);

        Long reviewId = publisher.publish(7L, "o", "r", 7, "sha", ReviewResult.empty("x"), DIFF);

        assertNull(reviewId);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void propagatesGithubErrorSoCallerCanHandleIt() {
        server.enqueue(new MockResponse().setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"Validation Failed\"}"));
        GithubReviewPublisher publisher = publisher(true);

        ReviewResult result = new ReviewResult("s", List.of(
                new ReviewFinding("Foo.java", 2, Severity.ERROR, "Bug", "d")));

        assertThrows(RestClientResponseException.class,
                () -> publisher.publish(7L, "o", "r", 7, "sha", result, DIFF));
    }

    private GithubReviewPublisher publisher(boolean postEmpty) {
        GithubAppAuthService auth = mock(GithubAppAuthService.class);
        when(auth.getInstallationToken(7L)).thenReturn("ghs_token");
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        GithubApiClient apiClient = new GithubApiClient(restClient, auth);
        DiffLineMapper mapper = new DiffLineMapper(new DiffParser(20));
        return new GithubReviewPublisher(apiClient, mapper, postEmpty);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
