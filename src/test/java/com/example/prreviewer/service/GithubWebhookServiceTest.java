package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

class GithubWebhookServiceTest {

    private static final String SECRET = "mysecret";

    private final GithubWebhookService service = new GithubWebhookService(
            new ObjectMapper(), mock(GithubApiClient.class), mock(ReviewProcessingService.class), SECRET);

    @Test
    void acceptsValidSignature() throws IOException {
        byte[] body = payload();
        assertTrue(service.isValidSignature(body, "sha256=" + hmacHex(SECRET, body)));
    }

    @Test
    void rejectsWrongSignature() throws IOException {
        byte[] body = payload();
        String wrong = "sha256=" + hmacHex("not-the-secret", body);
        assertFalse(service.isValidSignature(body, wrong));
    }

    @Test
    void rejectsMissingSignature() throws IOException {
        assertFalse(service.isValidSignature(payload(), null));
    }

    @Test
    void rejectsTamperedBody() throws IOException {
        byte[] body = payload();
        String sig = "sha256=" + hmacHex(SECRET, body);
        byte[] tampered = new String(body, StandardCharsets.UTF_8)
                .replace("octocat/Hello-World", "attacker/evil-repo")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(service.isValidSignature(tampered, sig));
    }

    @Test
    void parsesPullRequestFields() throws IOException {
        PullRequestInfo info = service.parsePullRequest(payload());
        assertEquals("octocat/Hello-World", info.repoFullName());
        assertEquals(42L, info.prNumber());
        assertEquals("opened", info.action());
        assertEquals("6dcb09b5b57875f334f61aebed695e2e4193db5e", info.headSha());
        assertEquals(12345678L, info.installationId());
        assertEquals("octocat", info.owner());
        assertEquals("Hello-World", info.repo());
        assertEquals("octocat", info.accountLogin());
        assertEquals("main", info.defaultBranch());
    }

    private static byte[] payload() throws IOException {
        try (var in = GithubWebhookServiceTest.class
                .getResourceAsStream("/github/pull_request-opened.json")) {
            return in.readAllBytes();
        }
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
