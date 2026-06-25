package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;

import com.example.prreviewer.dto.ReviewContext;
import com.example.prreviewer.dto.ReviewFinding;
import com.example.prreviewer.dto.ReviewResult;
import com.example.prreviewer.dto.Severity;

class ReviewServiceTest {

    private static final ReviewContext CTX = new ReviewContext("octocat/Hello-World", 42, "abc123");

    // A two-file unified diff.
    private static final String TWO_FILE_DIFF = """
            diff --git a/Foo.java b/Foo.java
            index 1111111..2222222 100644
            --- a/Foo.java
            +++ b/Foo.java
            @@ -1 +1 @@
            +int x = 1 / 0;
            diff --git a/Bar.java b/Bar.java
            index 3333333..4444444 100644
            --- a/Bar.java
            +++ b/Bar.java
            @@ -1 +1 @@
            +String s = null;
            """;

    @Test
    void analyzesEachFileInItsOwnModelCall() throws IOException {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                resp(finding("ignored", "ERROR", "div by zero")),
                resp(finding("ignored", "WARNING", "null assignment")));
        ReviewService service = service(model, Severity.INFO, 10);

        ReviewResult result = service.review(TWO_FILE_DIFF, CTX);

        verify(model, times(2)).call(any(Prompt.class));
        assertEquals(2, result.findings().size());
        // filePath is forced to the analyzed file, regardless of what the model said.
        Set<String> paths = result.findings().stream()
                .map(ReviewFinding::filePath).collect(Collectors.toSet());
        assertEquals(Set.of("Foo.java", "Bar.java"), paths);
    }

    @Test
    void severityThresholdAppliesAcrossAllFiles() throws IOException {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                resp(finding("Foo.java", "INFO", "nit")),
                resp(finding("Bar.java", "ERROR", "real bug")));
        ReviewService service = service(model, Severity.WARNING, 10);

        ReviewResult result = service.review(TWO_FILE_DIFF, CTX);

        assertEquals(1, result.findings().size());
        assertEquals(Severity.ERROR, result.findings().get(0).severity());
    }

    @Test
    void findingsCapAppliesAcrossAllFiles() throws IOException {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                resp(twoFindings("ERROR", "WARNING")),
                resp(twoFindings("ERROR", "WARNING")));
        ReviewService service = service(model, Severity.INFO, 3);

        ReviewResult result = service.review(TWO_FILE_DIFF, CTX);

        // 4 findings produced across 2 files, capped at 3, highest severity kept.
        assertEquals(3, result.findings().size());
        assertEquals(2, result.findings().stream().filter(f -> f.severity() == Severity.ERROR).count());
    }

    @Test
    void oneFailingFileDoesNotStopTheOthers() throws IOException {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                resp("not valid json"),
                resp(finding("Bar.java", "ERROR", "real bug")));
        ReviewService service = service(model, Severity.WARNING, 10);

        ReviewResult result = service.review(TWO_FILE_DIFF, CTX);

        assertEquals(1, result.findings().size());
        assertEquals("Bar.java", result.findings().get(0).filePath());
    }

    @Test
    void allFilesFailingThrows() throws IOException {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(resp("garbage"), resp("garbage"));
        ReviewService service = service(model, Severity.WARNING, 10);

        assertThrows(ReviewFailedException.class, () -> service.review(TWO_FILE_DIFF, CTX));
    }

    @Test
    void emptyDiffReturnsEmptyResultWithoutCallingModel() throws IOException {
        ChatModel model = mock(ChatModel.class);
        ReviewService service = service(model, Severity.WARNING, 10);

        ReviewResult result = service.review("   ", CTX);

        assertTrue(result.findings().isEmpty());
        verify(model, never()).call(any(Prompt.class));
    }

    private static ReviewService service(ChatModel model, Severity threshold, int maxFindings)
            throws IOException {
        return new ReviewService(
                ChatClient.builder(model),
                new DiffParser(20),
                new ByteArrayResource("system prompt".getBytes(StandardCharsets.UTF_8)),
                12_000, threshold, maxFindings);
    }

    private static ChatResponse resp(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private static String finding(String path, String severity, String title) {
        return """
                {"summary":"s","findings":[
                  {"filePath":"%s","line":1,"severity":"%s","title":"%s","detail":"d"}
                ]}""".formatted(path, severity, title);
    }

    private static String twoFindings(String sev1, String sev2) {
        return """
                {"summary":"s","findings":[
                  {"filePath":"X","line":1,"severity":"%s","title":"a","detail":"d"},
                  {"filePath":"X","line":2,"severity":"%s","title":"b","detail":"d"}
                ]}""".formatted(sev1, sev2);
    }
}
