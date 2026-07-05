package com.example.prreviewer.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.prreviewer.config.RabbitConfig;
import com.example.prreviewer.entity.Review;
import com.example.prreviewer.entity.ReviewStatus;
import com.example.prreviewer.repository.InstallationRepository;
import com.example.prreviewer.repository.RepositoryRepository;
import com.example.prreviewer.repository.ReviewCommentRepository;
import com.example.prreviewer.repository.ReviewRepository;
import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * One high-value end-to-end test: a signed pull_request webhook flows through
 * the real queue and DB, is analyzed (LLM mocked), persisted, and posted back to
 * GitHub (mocked with WireMock) as a single review. Real PostgreSQL + RabbitMQ +
 * Redis via Testcontainers; only the external edges are stubbed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("integration")
class WebhookEndToEndTest {

    private static final String WEBHOOK_SECRET = "e2e-secret";
    private static final String HEAD_SHA = "6dcb09b5b57875f334f61aebed695e2e4193db5e";

    private static final String DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            index 1111111..2222222 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -0,0 +1,2 @@
            +int x = 1 / 0;
            +int y = 2;
            """;

    private static final String REVIEW_JSON = """
            {"summary":"One serious bug.","findings":[
              {"filePath":"src/Foo.java","line":1,"severity":"ERROR",
               "title":"Division by zero","detail":"1 / 0 throws ArithmeticException"}
            ]}""";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());
    static Path privateKeyFile;

    static {
        WIREMOCK.start();
        privateKeyFile = writeTempRsaKey();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("github.api.base-url", WIREMOCK::baseUrl);
        registry.add("github.app.id", () -> "123");
        registry.add("github.app.private-key-path", () -> privateKeyFile.toString());
        registry.add("github.webhook.secret", () -> WEBHOOK_SECRET);
    }

    // The LLM: return fixed structured findings, no real model call.
    @MockitoBean
    ChatModel chatModel;

    @Autowired
    TestRestTemplate restTemplate;
    @Autowired
    ReviewRepository reviewRepository;
    @Autowired
    ReviewCommentRepository reviewCommentRepository;
    @Autowired
    RepositoryRepository repositoryRepository;
    @Autowired
    InstallationRepository installationRepository;
    @Autowired
    RabbitAdmin rabbitAdmin;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(REVIEW_JSON)))));

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlPathMatching("/app/installations/.*/access_tokens"))
                .willReturn(okJson("{\"token\":\"ghs_e2e\",\"expires_at\":\"2999-01-01T00:00:00Z\"}")));
        WIREMOCK.stubFor(get(urlPathEqualTo("/repos/octocat/Hello-World/pulls/42"))
                .willReturn(aResponse().withStatus(200).withBody(DIFF)));
        WIREMOCK.stubFor(post(urlPathEqualTo("/repos/octocat/Hello-World/pulls/42/reviews"))
                .willReturn(okJson("{\"id\":987}")));

        // Isolation: start each test from a clean DB + Redis.
        reviewRepository.deleteAll();
        repositoryRepository.deleteAll();
        installationRepository.deleteAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        rabbitAdmin.purgeQueue(RabbitConfig.QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.DLQ, false);
    }

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        repositoryRepository.deleteAll();
        installationRepository.deleteAll();
    }

    @AfterAll
    static void stopWiremock() {
        WIREMOCK.stop();
    }

    @Test
    void signedPullRequestWebhook_isReviewedPersistedAndPostedBackToGithub() throws Exception {
        byte[] body = readPayload();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-GitHub-Event", "pull_request");
        headers.add("X-GitHub-Delivery", "e2e-delivery-1");
        headers.add("X-Hub-Signature-256", "sha256=" + hmacHex(WEBHOOK_SECRET, body));

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/webhooks/github", new HttpEntity<>(body, headers), Void.class);
        assertEquals(200, response.getStatusCode().value());

        // The heavy work happens asynchronously on the consumer; wait for the
        // review to be persisted DONE with the GitHub review id.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Review> reviews = reviewRepository.findAll();
            assertEquals(1, reviews.size());
            Review review = reviews.get(0);
            assertEquals(ReviewStatus.DONE, review.getStatus());
            assertEquals(987L, review.getGithubReviewId());
            assertFalse(reviewCommentRepository.findByReviewId(review.getId()).isEmpty());
        });

        // Exactly one review posted to GitHub, event=COMMENT, for the analyzed commit.
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/repos/octocat/Hello-World/pulls/42/reviews"))
                .withRequestBody(matchingJsonPath("$.event", equalTo("COMMENT")))
                .withRequestBody(matchingJsonPath("$.commit_id", equalTo(HEAD_SHA))));
    }

    private static byte[] readPayload() throws Exception {
        try (var in = WebhookEndToEndTest.class.getResourceAsStream("/github/pull_request-opened.json")) {
            return in.readAllBytes();
        }
    }

    private static String hmacHex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static Path writeTempRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                            .encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Path file = Files.createTempFile("e2e-app-key", ".pem");
            Files.writeString(file, pem);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
