package com.example.prreviewer.dto;

/**
 * A single issue the reviewer found.
 *
 * @param filePath path of the file the issue is in
 * @param line     1-based line number, or null if not line-specific
 * @param severity how serious the issue is
 * @param title    short, specific headline
 * @param detail   explanation of the problem (and ideally the fix)
 */
public record ReviewFinding(String filePath, Integer line, Severity severity, String title, String detail) {
}
