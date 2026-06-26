package com.example.prreviewer.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.prreviewer.dto.DiffFile;

/**
 * Computes, per file, the set of RIGHT-side line numbers that can carry an
 * inline review comment.
 * <p>
 * GitHub rejects (422 "line must be part of the diff") any inline comment whose
 * line is not in the posted diff. We mirror GitHub's own accounting: within a
 * hunk {@code @@ -a,b +c,d @@}, the RIGHT line counter starts at {@code c};
 * added ({@code +}) and context ({@code space}) lines advance it and are
 * commentable; removed ({@code -}) lines are LEFT-only and do not advance it.
 */
@Component
public class DiffLineMapper {

    // Capture the RIGHT-side start line (c) from "@@ -a,b +c,d @@".
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private final DiffParser diffParser;

    public DiffLineMapper(DiffParser diffParser) {
        this.diffParser = diffParser;
    }

    public Map<String, Set<Integer>> commentableLines(String rawDiff) {
        Map<String, Set<Integer>> result = new HashMap<>();
        for (DiffFile file : diffParser.parse(rawDiff)) {
            result.put(file.filePath(), commentableLinesForFile(file.diff()));
        }
        return result;
    }

    private static Set<Integer> commentableLinesForFile(String fileDiff) {
        Set<Integer> lines = new HashSet<>();
        int rightLine = -1; // -1 = not currently inside a hunk

        for (String line : fileDiff.split("\n", -1)) {
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.find()) {
                rightLine = Integer.parseInt(header.group(1));
                continue;
            }
            if (rightLine < 0) {
                // still in the file header (diff --git / index / --- / +++)
                continue;
            }
            if (line.startsWith("+")) {            // added -> RIGHT, commentable
                lines.add(rightLine);
                rightLine++;
            } else if (line.startsWith("-")) {     // removed -> LEFT only
                // does not advance the RIGHT counter
            } else if (line.startsWith(" ")) {     // context -> RIGHT, commentable
                lines.add(rightLine);
                rightLine++;
            }
            // Anything else (a blank "" padding line, "\ No newline...", etc.) is
            // not a real diff body line and does not advance the RIGHT counter.
        }
        return lines;
    }
}
