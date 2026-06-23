package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

class GithubAppAuthServiceTest {

    private static final long APP_ID = 123456L;

    private KeyPair keyPair;
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void appJwtHasCorrectClaimsAndIsRs256Signed() {
        Instant fixedNow = Instant.parse("2026-06-23T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        GithubAppAuthService service = new GithubAppAuthService(
                APP_ID, keyPair.getPrivate(), null, RestClient.builder().build(), fixedClock);

        String jwt = service.createAppJwt();

        // Validate against the same fixed instant, otherwise the parser uses the
        // real wall clock and rejects this (intentionally short-lived) token.
        Jws<Claims> parsed = Jwts.parser()
                .clock(() -> Date.from(fixedNow))
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(jwt);

        assertEquals("RS256", parsed.getHeader().getAlgorithm());
        assertEquals(String.valueOf(APP_ID), parsed.getPayload().getIssuer());
        // iat is backdated 60s for clock skew; exp is iat + 10 minutes.
        assertEquals(Date.from(fixedNow.minusSeconds(60)), parsed.getPayload().getIssuedAt());
        assertEquals(Date.from(fixedNow.minusSeconds(60).plusSeconds(600)), parsed.getPayload().getExpiration());
    }

    @Test
    void cachesInstallationTokenAndDoesNotCallNetworkTwice() throws Exception {
        // expires far in the future so the cached token stays valid.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":\"ghs_cachedtoken\",\"expires_at\":\"2999-01-01T00:00:00Z\"}"));

        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        GithubAppAuthService service = new GithubAppAuthService(
                APP_ID, keyPair.getPrivate(), null, restClient, Clock.systemUTC());

        String first = service.getInstallationToken(999L);
        String second = service.getInstallationToken(999L);

        assertEquals("ghs_cachedtoken", first);
        assertEquals(first, second);
        // Second call served from cache -> only one network request total.
        assertEquals(1, server.getRequestCount());

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/app/installations/999/access_tokens", request.getPath());
        assertTrue(request.getHeader("Authorization").startsWith("Bearer "));
    }
}
