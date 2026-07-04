package com.example.prreviewer.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import com.example.prreviewer.dto.ReviewJobMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

class ReviewJobMessageSerializationTest {

    // Same shape as the app bean: application ObjectMapper (with modules) so the
    // record deserializes by constructor parameter names.
    private final Jackson2JsonMessageConverter converter =
            new Jackson2JsonMessageConverter(new ObjectMapper().findAndRegisterModules());

    @Test
    void recordRoundTripsThroughJson() {
        ReviewJobMessage original = new ReviewJobMessage(
                555L, "octocat", "Hello-World", 42, "sha123", "opened", "delivery-9");

        Message message = converter.toMessage(original, new MessageProperties());

        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"deliveryId\":\"delivery-9\""), json);
        assertTrue(json.contains("\"prNumber\":42"), json);
        assertTrue(json.contains("\"owner\":\"octocat\""), json);

        Object back = converter.fromMessage(message);
        assertEquals(original, back);
    }
}
