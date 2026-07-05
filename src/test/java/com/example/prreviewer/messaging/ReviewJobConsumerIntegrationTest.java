package com.example.prreviewer.messaging;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.dto.ReviewJobMessage;
import com.example.prreviewer.service.GithubApiClient;
import com.example.prreviewer.service.ReviewProcessingService;

/**
 * Consumer end-to-end against real RabbitMQ + PostgreSQL (Testcontainers).
 * The heavy services are mocked so we can assert the routing/retry/DLQ behavior
 * deterministically. Retry intervals are shortened so the DLQ test is quick.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.max-attempts=3",
        "spring.rabbitmq.listener.simple.retry.initial-interval=150ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=300ms"
})
class ReviewJobConsumerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    GithubApiClient githubApiClient;

    @MockitoBean
    ReviewProcessingService processing;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @BeforeEach
    void purgeQueues() {
        rabbitAdmin.purgeQueue(RabbitConfig.QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.DLQ, false);
    }

    @Test
    void consumesJobAndRunsProcessingChain() {
        when(githubApiClient.fetchPullRequestDiff(555L, "octocat", "Hello-World", 42))
                .thenReturn("diff --git a/Foo.java b/Foo.java\n+x");

        publish(job("delivery-ok"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                verify(processing).processPullRequest(any(), eq("diff --git a/Foo.java b/Foo.java\n+x")));
    }

    @Test
    void deadLettersAfterRetriesExhausted() {
        when(githubApiClient.fetchPullRequestDiff(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn("diff --git a/Foo.java b/Foo.java\n+x");
        // Transient failure on every attempt -> retried, then dead-lettered.
        doThrow(new RuntimeException("boom")).when(processing).processPullRequest(any(), any());

        publish(job("delivery-retry"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Object dead = rabbitTemplate.receiveAndConvert(RabbitConfig.DLQ);
            assertNotNull(dead, "expected the job in the DLQ");
            assertInstanceOf(ReviewJobMessage.class, dead);
        });
        // Attempted maxAttempts times before dead-lettering (dedupe never blocked it).
        verify(processing, times(3)).processPullRequest(any(), any());
    }

    private void publish(ReviewJobMessage job) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, job);
    }

    private static ReviewJobMessage job(String deliveryId) {
        return new ReviewJobMessage(555L, "octocat", "Hello-World", 42, "sha123", "opened", deliveryId);
    }
}
