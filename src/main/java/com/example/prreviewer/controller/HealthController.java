package com.example.prreviewer.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight application-level liveness endpoint.
 * <p>
 * This is intentionally separate from Spring Boot Actuator's {@code /actuator/health},
 * which also reports component health (DB, disk, etc.). This endpoint is a simple,
 * dependency-free "is the app process answering HTTP?" probe.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "pr-reviewer",
                "timestamp", Instant.now().toString()
        );
    }
}
