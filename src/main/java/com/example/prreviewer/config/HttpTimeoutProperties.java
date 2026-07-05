package com.example.prreviewer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connect/read timeouts for outbound HTTP: the GitHub API and the LLM. Read from
 * config so no external call can hang indefinitely, and so the (much longer) LLM
 * read timeout can differ from GitHub's.
 */
@ConfigurationProperties(prefix = "app.http")
public class HttpTimeoutProperties {

    private final Timeouts github;
    private final Timeouts llm;

    public HttpTimeoutProperties(Timeouts github, Timeouts llm) {
        this.github = github != null ? github : new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(15));
        this.llm = llm != null ? llm : new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(120));
    }

    public Timeouts getGithub() {
        return github;
    }

    public Timeouts getLlm() {
        return llm;
    }

    public static class Timeouts {
        private final Duration connectTimeout;
        private final Duration readTimeout;

        public Timeouts(Duration connectTimeout, Duration readTimeout) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }
    }
}
