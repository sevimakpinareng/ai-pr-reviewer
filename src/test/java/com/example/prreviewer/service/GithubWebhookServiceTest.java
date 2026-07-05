package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.dto.ReviewJobMessage;
import com.example.prreviewer.service.GithubWebhookService.PullRequestInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

class GithubWebhookServiceTest {

    private static final String SECRET = "mysecret";

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final PrSupersedingService supersedingService = mock(PrSupersedingService.class);
    private final GithubWebhookService service =
            new GithubWebhookService(new ObjectMapper(), rabbitTemplate, supersedingService, SECRET);

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
    void rejectsSignatureWithoutSha256Prefix() throws IOException {
        byte[] body = payload();
        // Correct HMAC hex but missing the "sha256=" scheme prefix GitHub sends.
        String bareHex = hmacHex(SECRET, body);
        assertFalse(service.isValidSignature(body, bareHex));
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
    }

    @Test
    void publishesJobForRelevantPullRequest() throws IOException {
        service.handleEvent("pull_request", "delivery-123", payload());

        ArgumentCaptor<ReviewJobMessage> captor = ArgumentCaptor.forClass(ReviewJobMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY), captor.capture());

        ReviewJobMessage job = captor.getValue();
        assertEquals(12345678L, job.installationId());
        assertEquals("octocat", job.owner());
        assertEquals("Hello-World", job.repo());
        assertEquals(42, job.prNumber());
        assertEquals("6dcb09b5b57875f334f61aebed695e2e4193db5e", job.commitSha());
        assertEquals("opened", job.action());
        assertEquals("delivery-123", job.deliveryId());

        // Latest commit is recorded (for superseding) before the job is published.
        verify(supersedingService).recordLatestCommit(
                "octocat", "Hello-World", 42, "6dcb09b5b57875f334f61aebed695e2e4193db5e");
    }

    @Test
    void doesNotPublishForIrrelevantAction() throws IOException {
        byte[] closed = new String(payload(), StandardCharsets.UTF_8)
                .replace("\"action\": \"opened\"", "\"action\": \"closed\"")
                .getBytes(StandardCharsets.UTF_8);

        service.handleEvent("pull_request", "d", closed);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void doesNotPublishForNonPullRequestEvent() throws IOException {
        service.handleEvent("issues", "d", payload());

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
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
