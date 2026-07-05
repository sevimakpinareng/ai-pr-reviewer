package com.example.prreviewer.actuator;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Prometheus scrape endpoint is exposed and exposes our business metrics
 * (pre-registered eagerly, so they appear even before any review runs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Tag("integration")
class ActuatorEndpointsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Test
    void prometheusEndpointExposesBusinessMetrics() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reviews_processed_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("review_duration_seconds")));
    }
}
