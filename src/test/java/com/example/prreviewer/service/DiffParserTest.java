package com.example.prreviewer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.prreviewer.dto.DiffFile;

class DiffParserTest {

    // Four segments: a modified file, a binary file, an added file, a deleted file.
    private static final String MULTI_FILE_DIFF = """
            diff --git a/A.java b/A.java
            index 1111111..2222222 100644
            --- a/A.java
            +++ b/A.java
            @@ -1 +1 @@
            -old
            +new
            diff --git a/img.png b/img.png
            new file mode 100644
            index 0000000..abc1234
            Binary files /dev/null and b/img.png differ
            diff --git a/New.java b/New.java
            new file mode 100644
            index 0000000..1234567
            --- /dev/null
            +++ b/New.java
            @@ -0,0 +1 @@
            +added line
            diff --git a/Old.java b/Old.java
            deleted file mode 100644
            index 7654321..0000000
            --- a/Old.java
            +++ /dev/null
            @@ -1 +0,0 @@
            -removed line
            """;

    @Test
    void splitsByFileAndSkipsBinary() {
        List<DiffFile> files = new DiffParser(20).parse(MULTI_FILE_DIFF);

        // img.png is binary and must be skipped.
        assertEquals(List.of("A.java", "New.java", "Old.java"),
                files.stream().map(DiffFile::filePath).toList());
        // Each slice carries its own header and only its own content.
        assertTrue(files.get(0).diff().startsWith("diff --git a/A.java b/A.java"));
        assertTrue(files.get(1).diff().contains("added line"));
        assertTrue(files.get(2).diff().contains("removed line"));
        assertTrue(files.stream().noneMatch(f -> f.diff().contains("img.png")));
    }

    @Test
    void capsNumberOfFiles() {
        List<DiffFile> files = new DiffParser(2).parse(MULTI_FILE_DIFF);

        assertEquals(2, files.size());
        assertEquals(List.of("A.java", "New.java"), files.stream().map(DiffFile::filePath).toList());
    }

    @Test
    void returnsEmptyForBlankDiff() {
        assertTrue(new DiffParser(20).parse("").isEmpty());
        assertTrue(new DiffParser(20).parse("   ").isEmpty());
        assertTrue(new DiffParser(20).parse(null).isEmpty());
    }

    @Test
    void returnsEmptyWhenOnlyBinaryFiles() {
        String onlyBinary = """
                diff --git a/img.png b/img.png
                new file mode 100644
                index 0000000..abc1234
                Binary files /dev/null and b/img.png differ
                """;
        assertTrue(new DiffParser(20).parse(onlyBinary).isEmpty());
    }
}
