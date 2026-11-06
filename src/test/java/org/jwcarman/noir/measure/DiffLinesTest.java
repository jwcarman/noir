package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Input is one {@code git diff -U0 base branch -- paths}, as {@code Bundles.diffSince} gives. */
class DiffLinesTest {
  private static final String A = "src/main/java/a/A.java";
  private static final String B = "src/main/java/a/B.java";

  private static String file(String path, String hunks) {
    return "diff --git a/"
        + path
        + " b/"
        + path
        + "\nindex 1111111..2222222 100644\n--- a/"
        + path
        + "\n+++ b/"
        + path
        + "\n"
        + hunks;
  }

  @Test
  void addedLinesPerFile() {
    var diff = file(A, "@@ -2 +2,2 @@\n-b\n+B\n+C\n") + file(B, "@@ -0,0 +1,2 @@\n+x\n+y\n");
    assertThat(DiffLines.added(diff, Set.of(A, B)))
        .isEqualTo(Map.of(A, Set.of(2, 3), B, Set.of(1, 2)));
  }

  @Test
  void ignoresOtherFiles() {
    var diff = file(A, "@@ -1 +1 @@\n-a\n+b\n") + file("pom.xml", "@@ -1,0 +2 @@\n+y\n");
    assertThat(DiffLines.added(diff, Set.of(A))).isEqualTo(Map.of(A, Set.of(1)));
  }

  @Test
  void handlesMultipleHunks() {
    var diff = file(A, "@@ -2,0 +3 @@\n+new\n@@ -21 +21,0 @@\n-u\n@@ -40,0 +40,2 @@\n+p\n+q\n");
    assertThat(DiffLines.added(diff, Set.of(A))).isEqualTo(Map.of(A, Set.of(3, 40, 41)));
  }

  @Test
  void contextLinesAreNotAdded() {
    var diff = file(A, "@@ -1,3 +1,4 @@\n a\n-b\n+B\n+C\n c\n");
    assertThat(DiffLines.added(diff, Set.of(A))).isEqualTo(Map.of(A, Set.of(2, 3)));
  }

  @Test
  void deletionOnlyFileHasEmptySet() {
    var diff = file(A, "@@ -2 +1,0 @@\n-b\n");
    assertThat(DiffLines.added(diff, Set.of(A, B))).isEqualTo(Map.of(A, Set.of(), B, Set.of()));
    assertThat(DiffLines.added("", Set.of(A))).isEqualTo(Map.of(A, Set.of()));
  }

  @Test
  void noNewlineMarkerIsNotALine() {
    var diff =
        file(
            A, "@@ -2 +2 @@\n-b\n\\ No newline at end of file\n+b\n\\ No newline at end of file\n");
    assertThat(DiffLines.added(diff, Set.of(A))).isEqualTo(Map.of(A, Set.of(2)));
  }

  @Test
  void binaryPatchHasNoLines() {
    var diff =
        "diff --git a/img.png b/img.png\nindex 1..2 100644\nBinary files a/img.png and"
            + " b/img.png differ\n"
            + file(A, "@@ -1 +1 @@\n-a\n+b\n");
    assertThat(DiffLines.added(diff, Set.of(A, "img.png")))
        .isEqualTo(Map.of(A, Set.of(1), "img.png", Set.of()));
  }

  @Test
  void pathsWithSpacesAndQuotedPathsAreMatched() {
    var spaced = "src/main/java/a/S p.java";
    var quoted = "src/main/java/a/é.java";
    var diff =
        "diff --git a/"
            + spaced
            + " b/"
            + spaced
            + "\nindex 1..2 100644\n--- a/"
            + spaced
            + "\t\n+++ b/"
            + spaced
            + "\t\n@@ -1 +1 @@\n-a\n+b\n"
            + "diff --git \"a/src/main/java/a/\\303\\251.java\" \"b/src/main/java/a/\\303\\251.java\"\n"
            + "index 1..2 100644\n--- \"a/src/main/java/a/\\303\\251.java\"\n"
            + "+++ \"b/src/main/java/a/\\303\\251.java\"\n@@ -1,0 +2 @@\n+b\n";
    assertThat(DiffLines.added(diff, Set.of(spaced, quoted)))
        .isEqualTo(Map.of(spaced, Set.of(1), quoted, Set.of(2)));
  }

  @Test
  void newAndDeletedFiles() {
    var diff =
        "diff --git a/"
            + A
            + " b/"
            + A
            + "\nnew file mode 100644\nindex 0..1\n--- /dev/null\n+++ b/"
            + A
            + "\n@@ -0,0 +1,2 @@\n+c\n+d\n"
            + "diff --git a/"
            + B
            + " b/"
            + B
            + "\ndeleted file mode 100644\nindex 1..0\n--- a/"
            + B
            + "\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-a\n-b\n";
    assertThat(DiffLines.added(diff, Set.of(A, B))).isEqualTo(Map.of(A, Set.of(1, 2), B, Set.of()));
  }

  @Test
  void contentLinesThatLookLikeHeadersStayInTheHunk() {
    var diff = file(A, "@@ -1,0 +2,3 @@\n+diff --git a/x b/x\n+--- a/x\n+@@ -1 +1 @@\n");
    assertThat(DiffLines.added(diff, Set.of(A))).isEqualTo(Map.of(A, Set.of(2, 3, 4)));
  }

  @Test
  void crlfContentKeepsLineNumbers() {
    var diff = file(A, "@@ -1 +1,2 @@\r\n-a\r\n+b\r\n+c\r\n");
    assertThat(DiffLines.added(diff, Set.of(A))).isEqualTo(Map.of(A, Set.of(1, 2)));
  }
}
