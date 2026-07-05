package com.example.prreviewer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HttpTimeoutPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsTimeoutsFromConfiguration() {
        runner.withPropertyValues(
                "app.http.github.connect-timeout=3s",
                "app.http.github.read-timeout=9s",
                "app.http.llm.connect-timeout=4s",
                "app.http.llm.read-timeout=200s"
        ).run(context -> {
            HttpTimeoutProperties props = context.getBean(HttpTimeoutProperties.class);
            assertThat(props.getGithub().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.getGithub().getReadTimeout()).isEqualTo(Duration.ofSeconds(9));
            assertThat(props.getLlm().getConnectTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(props.getLlm().getReadTimeout()).isEqualTo(Duration.ofSeconds(200));
        });
    }

    @EnableConfigurationProperties(HttpTimeoutProperties.class)
    static class TestConfig {
    }
}
