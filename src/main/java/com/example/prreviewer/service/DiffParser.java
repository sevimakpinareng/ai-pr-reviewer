package com.example.prreviewer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.prreviewer.dto.DiffFile;

/**
 * Splits a raw unified diff into per-file slices, on {@code diff --git}
 * boundaries. Binary files are skipped, and the number of files is capped so a
 * huge PR cannot fan out into an unbounded number of model calls.
 */
@Component
public class DiffParser {

    private static final Logger log = LoggerFactory.getLogger(DiffParser.class);

    private static final String FILE_HEADER = "diff --git ";
    // Captures the "b/" (new) path from: diff --git a/<old> b/<new>
    private static final Pattern HEADER_PATH = Pattern.compile("^diff --git a/.+ b/(.+)$");

    private final int maxFiles;

    public DiffParser(@Value("${app.review.max-files:20}") int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public List<DiffFile> parse(String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return List.of();
        }

        List<DiffFile> files = new ArrayList<>();
        String[] lines = rawDiff.split("\n", -1);

        StringBuilder current = null;
        String currentPath = null;
        boolean binary = false;

        for (String line : lines) {
            if (line.startsWith(FILE_HEADER)) {
                flush(files, current, currentPath, binary);
                current = new StringBuilder();
                currentPath = extractPath(line);
                binary = false;
            }
            if (current != null) {
                if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                    binary = true;
                }
                current.append(line).append('\n');
            }
        }
        flush(files, current, currentPath, binary);

        if (files.size() > maxFiles) {
            log.warn("Diff has {} files; capping review to the first {}", files.size(), maxFiles);
            return List.copyOf(files.subList(0, maxFiles));
        }
        return files;
    }

    private static void flush(List<DiffFile> files, StringBuilder current, String path, boolean binary) {
        if (current == null || path == null) {
            return;
        }
        if (binary) {
            log.debug("Skipping binary file in diff: {}", path);
            return;
        }
        files.add(new DiffFile(path, current.toString()));
    }

    private static String extractPath(String headerLine) {
        Matcher matcher = HEADER_PATH.matcher(headerLine);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        log.debug("Could not parse file path from diff header: {}", headerLine);
        return null;
    }
}
