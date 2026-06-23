package com.example.prreviewer.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.prreviewer.service.GithubWebhookService;

/**
 * Receives GitHub webhook deliveries.
 * <p>
 * The body is taken as a raw {@code byte[]} on purpose: the HMAC signature must
 * be verified over the exact bytes GitHub signed. Letting Spring deserialize to
 * an object and re-serialize would change those bytes (key order, whitespace,
 * unicode escaping) and break verification.
 */
@RestController
public class WebhookController {

    private final GithubWebhookService webhookService;

    public WebhookController(GithubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType) {

        if (!webhookService.isValidSignature(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        webhookService.handleEvent(eventType, body);
        return ResponseEntity.ok().build();
    }
}
