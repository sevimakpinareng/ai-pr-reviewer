package com.example.prreviewer.dto;

/**
 * Severity of a review finding, ordered least-to-most severe.
 * Ordinal order matters: it drives threshold filtering and ranking.
 */
public enum Severity {
    INFO,
    WARNING,
    ERROR
}
