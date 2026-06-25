package com.example.prreviewer.dto;

import java.util.List;

/**
 * The structured outcome of reviewing a diff.
 *
 * @param summary  one-paragraph overall assessment
 * @param findings concrete issues; empty when nothing significant was found
 */
public record ReviewResult(String summary, List<ReviewFinding> findings) {

    public static ReviewResult empty(String summary) {
        return new ReviewResult(summary, List.of());
    }
}
