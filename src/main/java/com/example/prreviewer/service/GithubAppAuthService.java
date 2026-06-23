package com.example.prreviewer.service;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.prreviewer.config.RsaPrivateKeyLoader;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.jsonwebtoken.Jwts;

/**
 * Authenticates as a GitHub App and mints short-lived installation tokens.
 * <p>
 * Flow: a 10-minute, RS256-signed <em>app JWT</em> proves we are the App, which
 * we exchange at {@code POST /app/installations/{id}/access_tokens} for an
 * <em>installation token</em> (~1h) that authorizes API calls for that
 * installation. Installation tokens are cached in memory per installation so we
 * do not request a fresh one on every API call.
 */
@Service
public class GithubAppAuthService {

    private static final Logger log = LoggerFactory.getLogger(GithubAppAuthService.class);

    /** GitHub recommends backdating iat to tolerate small clock skew. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    /** App JWTs may live at most 10 minutes; keep them short. */
    private static final Duration JWT_TTL = Duration.ofMinutes(10);
    /** Refresh installation tokens slightly before they actually expire. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);

    private final long appId;
    private final String privateKeyPath;
    private final RestClient githubRestClient;
    private final Clock clock;

    private final ConcurrentHashMap<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private volatile PrivateKey privateKey;

    public GithubAppAuthService(@Value("${github.app.id:0}") long appId,
                                @Value("${github.app.private-key-path:}") String privateKeyPath,
                                RestClient githubRestClient,
                                Clock githubClock) {
        this(appId, null, privateKeyPath, githubRestClient, githubClock);
    }

    /** Test/explicit-key constructor: pass the key directly, skip file loading. */
    GithubAppAuthService(long appId,
                         PrivateKey privateKey,
                         String privateKeyPath,
                         RestClient githubRestClient,
                         Clock githubClock) {
        this.appId = appId;
        this.privateKey = privateKey;
        this.privateKeyPath = privateKeyPath;
        this.githubRestClient = githubRestClient;
        this.clock = githubClock;
    }

    /**
     * Builds a short-lived app-level JWT signed with RS256.
     * iss = appId, iat = now - 60s (skew), exp = iat + 10m.
     */
    public String createAppJwt() {
        Instant now = clock.instant();
        Instant issuedAt = now.minus(CLOCK_SKEW);
        Instant expiresAt = issuedAt.plus(JWT_TTL);

        return Jwts.builder()
                .issuer(String.valueOf(appId))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Returns a valid installation token for the given installation, reusing a
     * cached one until it is about to expire.
     */
    public String getInstallationToken(long installationId) {
        CachedToken cached = tokenCache.get(installationId);
        if (cached != null && clock.instant().isBefore(cached.expiresAt().minus(TOKEN_REFRESH_MARGIN))) {
            return cached.token();
        }

        InstallationTokenResponse response = githubRestClient.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAppJwt())
                .accept(MediaType.valueOf("application/vnd.github+json"))
                .retrieve()
                .body(InstallationTokenResponse.class);

        if (response == null || response.token() == null) {
            throw new IllegalStateException("GitHub returned an empty installation token response");
        }

        Instant expiresAt = Instant.parse(response.expiresAt());
        tokenCache.put(installationId, new CachedToken(response.token(), expiresAt));
        log.debug("Obtained new installation token for installation {} (expires {})", installationId, expiresAt);
        return response.token();
    }

    private PrivateKey privateKey() {
        PrivateKey key = privateKey;
        if (key == null) {
            synchronized (this) {
                if (privateKey == null) {
                    if (privateKeyPath == null || privateKeyPath.isBlank()) {
                        throw new IllegalStateException(
                                "GITHUB_PRIVATE_KEY_PATH is not configured; cannot authenticate as the GitHub App");
                    }
                    privateKey = RsaPrivateKeyLoader.loadFromPem(Path.of(privateKeyPath));
                }
                key = privateKey;
            }
        }
        return key;
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

    private record InstallationTokenResponse(String token, @JsonProperty("expires_at") String expiresAt) {
    }
}
