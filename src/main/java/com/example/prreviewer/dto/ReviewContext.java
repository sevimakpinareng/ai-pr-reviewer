package com.example.prreviewer.dto;

/**
 * Identifies what is being reviewed. Used for logging/correlation; the model
 * itself only needs the diff.
 */
public record ReviewContext(String repoFullName, int prNumber, String commitSha) {
}
