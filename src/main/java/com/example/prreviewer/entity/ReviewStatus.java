package com.example.prreviewer.entity;

/**
 * Lifecycle of a review row.
 * PROCESSING: created, analysis in flight. DONE: finished, comments written.
 * FAILED: analysis could not complete.
 */
public enum ReviewStatus {
    PROCESSING,
    DONE,
    FAILED
}
