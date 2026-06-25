package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;

import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;

class ReviewServiceTest {

    private static final String SAMPLE_DIFF = """
            diff --git a/Foo.java b/Foo.java
            +++ b/Foo.java
            @@ -1 +1 @@
            +int x = 1 / 0;
            """;

    @Test
    void parsesStructuredFindings() throws IOException {
        String json = """
                {
                  "summary": "One real bug found.",
                  "findings": [
                    {"filePath": "Foo.java", "line": 4, "severity": "ERROR",
                     "title": "Division by zero", "detail": "1 / 0 throws ArithmeticException."},
                    {"filePath": "Foo.java", "line": 4, "severity": "WARNING",
                     "title": "Magic number", "detail": "Extract the constant."}
                  ]
                }
                """;
        ReviewService service = serviceReturning(json, Severity.INFO, 10);

        ReviewResult result = service.review(SAMPLE_DIFF);

        assertEquals("One real bug found.", result.summary());
        assertEquals(2, result.findings().size());
        ReviewFinding first = result.findings().get(0);
        assertEquals("Foo.java", first.filePath());
        assertEquals(4, first.line());
        assertEquals(Severity.ERROR, first.severity());
        assertEquals("Division by zero", first.title());
    }

    @Test
    void severityThresholdDropsInfoFindings() throws IOException {
        String json = """
                {
                  "summary": "Mixed.",
                  "findings": [
                    {"filePath": "A.java", "line": 1, "severity": "INFO", "title": "nit", "detail": "x"},
                    {"filePath": "B.java", "line": 2, "severity": "WARNING", "title": "warn", "detail": "y"},
                    {"filePath": "C.java", "line": 3, "severity": "ERROR", "title": "err", "detail": "z"}
                  ]
                }
                """;
        ReviewService service = serviceReturning(json, Severity.WARNING, 10);

        ReviewResult result = service.review(SAMPLE_DIFF);

        assertEquals(2, result.findings().size());
        assertTrue(result.findings().stream().noneMatch(f -> f.severity() == Severity.INFO));
    }

    @Test
    void capsNumberOfFindingsKeepingHighestSeverity() throws IOException {
        String json = """
                {
                  "summary": "Many.",
                  "findings": [
                    {"filePath": "A.java", "line": 1, "severity": "INFO", "title": "i1", "detail": "x"},
                    {"filePath": "B.java", "line": 2, "severity": "ERROR", "title": "e1", "detail": "x"},
                    {"filePath": "C.java", "line": 3, "severity": "WARNING", "title": "w1", "detail": "x"},
                    {"filePath": "D.java", "line": 4, "severity": "ERROR", "title": "e2", "detail": "x"},
                    {"filePath": "E.java", "line": 5, "severity": "INFO", "title": "i2", "detail": "x"}
                  ]
                }
                """;
        // threshold INFO so nothing is filtered out before the cap takes effect.
        ReviewService service = serviceReturning(json, Severity.INFO, 3);

        ReviewResult result = service.review(SAMPLE_DIFF);

        assertEquals(3, result.findings().size());
        // The two ERRORs must survive; the lowest-severity INFOs must be dropped.
        assertEquals(2, result.findings().stream().filter(f -> f.severity() == Severity.ERROR).count());
        assertTrue(result.findings().stream().anyMatch(f -> f.severity() == Severity.WARNING));
    }

    @Test
    void malformedJsonDegradesToEmptyResult() throws IOException {
        ReviewService service = serviceReturning("this is not json at all", Severity.WARNING, 10);

        ReviewResult result = service.review(SAMPLE_DIFF);

        assertTrue(result.findings().isEmpty());
        assertFalse(result.summary().isBlank());
    }

    @Test
    void emptyFindingsAreHandled() throws IOException {
        String json = """
                {"summary": "Looks good, no significant issues.", "findings": []}
                """;
        ReviewService service = serviceReturning(json, Severity.WARNING, 10);

        ReviewResult result = service.review(SAMPLE_DIFF);

        assertEquals("Looks good, no significant issues.", result.summary());
        assertTrue(result.findings().isEmpty());
    }

    private static ReviewService serviceReturning(String json, Severity threshold, int maxFindings)
            throws IOException {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        return new ReviewService(
                ChatClient.builder(chatModel),
                new ByteArrayResource("system prompt".getBytes(StandardCharsets.UTF_8)),
                12_000, threshold, maxFindings);
    }
}
