package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DiffLineMapperTest {

    private final DiffLineMapper mapper = new DiffLineMapper(new DiffParser(20));

    @Test
    void computesCommentableRightLinesAcrossHunks() {
        // Hunk 1 starts at RIGHT line 1; hunk 2 at RIGHT line 11.
        String diff = """
                diff --git a/Foo.java b/Foo.java
                index 1111111..2222222 100644
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,3 +1,4 @@
                 context1
                -removed old
                +added new
                +another added
                 context4
                @@ -10,2 +11,3 @@
                 ctx11
                +added12
                 ctx13
                """;

        Set<Integer> lines = mapper.commentableLines(diff).get("Foo.java");

        // context (1,4,11,13) + added (2,3,12); the removed line never advances RIGHT.
        assertEquals(Set.of(1, 2, 3, 4, 11, 12, 13), lines);
    }

    @Test
    void addedAndContextAreCommentableRemovedIsNot() {
        String diff = """
                diff --git a/A.txt b/A.txt
                index 1111111..2222222 100644
                --- a/A.txt
                +++ b/A.txt
                @@ -5,2 +5,2 @@
                 keep
                -old line
                +new line
                """;

        Set<Integer> lines = mapper.commentableLines(diff).get("A.txt");

        assertTrue(lines.contains(5), "context line 5 is commentable");
        assertTrue(lines.contains(6), "added line at RIGHT 6 is commentable");
        // RIGHT only went 5 (context) -> 6 (added); nothing beyond.
        assertFalse(lines.contains(7));
        assertEquals(Set.of(5, 6), lines);
    }

    @Test
    void skipsBinaryFiles() {
        String diff = """
                diff --git a/img.png b/img.png
                new file mode 100644
                index 0000000..abc1234
                Binary files /dev/null and b/img.png differ
                """;

        assertTrue(mapper.commentableLines(diff).isEmpty());
    }

    @Test
    void renamedFileIsKeyedByNewPath() {
        String diff = """
                diff --git a/old/Name.java b/new/Name.java
                similarity index 90%
                rename from old/Name.java
                rename to new/Name.java
                index 1111111..2222222 100644
                --- a/old/Name.java
                +++ b/new/Name.java
                @@ -1 +1 @@
                -old
                +new
                """;

        Map<String, Set<Integer>> map = mapper.commentableLines(diff);

        assertTrue(map.containsKey("new/Name.java"));
        assertFalse(map.containsKey("old/Name.java"));
        assertEquals(Set.of(1), map.get("new/Name.java"));
    }

    @Test
    void newAndDeletedFilesMapTheirRightLines() {
        String diff = """
                diff --git a/New.java b/New.java
                new file mode 100644
                index 0000000..2222222
                --- /dev/null
                +++ b/New.java
                @@ -0,0 +1,2 @@
                +line one
                +line two
                diff --git a/Gone.java b/Gone.java
                deleted file mode 100644
                index 2222222..0000000
                --- a/Gone.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -was one
                -was two
                """;

        Map<String, Set<Integer>> map = mapper.commentableLines(diff);

        // Added file: both added lines are commentable on the RIGHT.
        assertEquals(Set.of(1, 2), map.get("New.java"));
        // Deleted file: nothing exists on the RIGHT, so no commentable lines.
        assertEquals(Set.of(), map.get("Gone.java"));
    }

    @Test
    void blankContextLineCountsButEmptyPaddingDoesNot() {
        // Permanent regression guard: a genuine blank line in the file is a
        // " " (space) context line and IS commentable; empty "" padding lines
        // (e.g. from a trailing newline) must NOT advance the RIGHT counter.
        // Built by hand because text blocks strip the trailing space of " ".
        String diff = String.join("\n",
                "diff --git a/A.java b/A.java",
                "index 1111111..2222222 100644",
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -1,3 +1,3 @@",
                " first",
                " ",          // blank line in the file -> context, RIGHT line 2
                " third",
                "");           // trailing empty padding -> must be ignored

        Set<Integer> lines = mapper.commentableLines(diff).get("A.java");

        assertEquals(Set.of(1, 2, 3), lines, "empty padding must not add line 4");
    }
}
