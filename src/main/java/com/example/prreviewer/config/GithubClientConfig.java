package com.example.prreviewer.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Shared beans for talking to the GitHub API.
 */
@Configuration
public class GithubClientConfig {

    /** Single RestClient pointed at the GitHub API base URL. */
    @Bean
    public RestClient githubRestClient(@Value("${github.api.base-url:https://api.github.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /** Injectable clock so JWT timing is deterministic under test. */
    @Bean
    public Clock githubClock() {
        return Clock.systemUTC();
    }
}
