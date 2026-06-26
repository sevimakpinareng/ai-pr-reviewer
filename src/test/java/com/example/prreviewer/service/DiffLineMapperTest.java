package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
