package com.example.prreviewer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.dto.ReviewJobMessage;
import com.example.prreviewer.service.GithubWebhookService;

@WebMvcTest(WebhookController.class)
@Import(GithubWebhookService.class)
@TestPropertySource(properties = "github.webhook.secret=mysecret")
class WebhookControllerTest {

    private static final String SECRET = "mysecret";

    @Autowired
    private MockMvc mvc;

    // The producer publishes through this; mocked so the test needs no broker.
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void validSignatureQueuesJobAndReturns200() throws Exception {
        byte[] body = payload();

        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "delivery-abc")
                        .header("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY), any(ReviewJobMessage.class));
    }

    @Test
    void invalidSignatureReturns401AndDoesNotQueue() throws Exception {
        byte[] body = payload();

        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void missingSignatureReturns401() throws Exception {
        byte[] body = payload();

        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    private static byte[] payload() throws IOException {
        try (var in = WebhookControllerTest.class
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
