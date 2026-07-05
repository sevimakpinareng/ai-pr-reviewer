package com.example.prreviewer.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.prreviewer.metrics.ReviewMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RabbitMQ topology for the review pipeline. Everything is durable so jobs
 * survive a broker restart.
 * <p>
 * The work queue dead-letters to a fanout DLX, so a job that exhausts its
 * retries (or is rejected as permanently broken) lands in {@code review.jobs.dlq}
 * instead of being lost or requeued forever.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "review.exchange";
    public static final String QUEUE = "review.jobs";
    public static final String ROUTING_KEY = "review.job";

    public static final String DLX = "review.dlx";
    public static final String DLQ = "review.jobs.dlq";

    @Bean
    DirectExchange reviewExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue reviewJobsQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX) // failures route here; no routing key -> original key reused
                .build();
    }

    @Bean
    Binding reviewJobsBinding() {
        return BindingBuilder.bind(reviewJobsQueue()).to(reviewExchange()).with(ROUTING_KEY);
    }

    @Bean
    FanoutExchange reviewDlx() {
        return new FanoutExchange(DLX, true, false);
    }

    @Bean
    Queue reviewJobsDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding reviewDlqBinding() {
        return BindingBuilder.bind(reviewJobsDlq()).to(reviewDlx());
    }

    /**
     * JSON converter used by BOTH the producer (RabbitTemplate) and the consumer
     * (listener container) — Spring Boot auto-wires this single MessageConverter
     * bean into both. Uses the application ObjectMapper so records deserialize
     * (parameter-names module is needed for that).
     */
    @Bean
    MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * When the listener's retries are exhausted, count the dead-letter (metric)
     * then delegate to the default recoverer, which rejects the message so it
     * routes to the DLQ. (Permanent errors bypass retry and are counted by the
     * consumer directly, so this only sees retry-exhaustion.)
     */
    @Bean
    MessageRecoverer dlqMeteringRecoverer(ReviewMetrics metrics) {
        RejectAndDontRequeueRecoverer delegate = new RejectAndDontRequeueRecoverer();
        return (message, cause) -> {
            metrics.recordDeadLetter("retries_exhausted");
            delegate.recover(message, cause);
        };
    }
}
