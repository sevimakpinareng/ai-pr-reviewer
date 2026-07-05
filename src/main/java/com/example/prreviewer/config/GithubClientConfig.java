package com.example.prreviewer.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.example.prreviewer.config.HttpTimeoutProperties.Timeouts;

/**
 * Shared HTTP client beans. Every outbound call has explicit connect/read
 * timeouts so a stuck peer can never hang a worker indefinitely.
 */
@Configuration
@EnableConfigurationProperties(HttpTimeoutProperties.class)
public class GithubClientConfig {

    /** RestClient pointed at the GitHub API base URL, with GitHub timeouts. */
    @Bean
    public RestClient githubRestClient(@Value("${github.api.base-url:https://api.github.com}") String baseUrl,
                                       HttpTimeoutProperties timeouts) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeouts.getGithub()))
                .build();
    }

    /**
     * Applies (longer) LLM timeouts to the RestClient.Builder that Spring AI uses
     * for the Ollama/Mistral clients. Our GitHub RestClient builds its own factory
     * above, so it is unaffected by this customizer.
     */
    @Bean
    public RestClientCustomizer llmRestClientCustomizer(HttpTimeoutProperties timeouts) {
        ClientHttpRequestFactory factory = requestFactory(timeouts.getLlm());
        return builder -> builder.requestFactory(factory);
    }

    /** Injectable clock so JWT timing is deterministic under test. */
    @Bean
    public Clock githubClock() {
        return Clock.systemUTC();
    }

    private static ClientHttpRequestFactory requestFactory(Timeouts timeouts) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(timeouts.getConnectTimeout())
                .withReadTimeout(timeouts.getReadTimeout());
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }
}
