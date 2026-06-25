package com.example.prreviewer.dto;

/**
 * One file's slice of a unified diff.
 *
 * @param filePath the (new-side) path of the changed file
 * @param diff     the raw diff text for just this file, including its
 *                 {@code diff --git} header
 */
public record DiffFile(String filePath, String diff) {
}
