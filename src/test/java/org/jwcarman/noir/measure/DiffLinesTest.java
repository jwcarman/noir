package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffLinesTest {
  private static final String A = "src/main/java/a/A.java";
  private static final String B = "src/main/java/a/B.java";

  private static String commit(String... fileDiffs) {
    return "commit 0123456789abcdef0123456789abcdef01234567\n"
        + "Author: x <x@y>\n"
        + "Date:   Thu Nov 5 23:38:00 2026 -0500\n"
        + "\n"
        + "    msg\n"
        + "    +not a diff line\n"
        + "\n"
        + String.join("", fileDiffs);
  }

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
    var patch =
        commit(
            file(A, "@@ -1,3 +1,4 @@\n a\n-b\n+B\n+C\n c\n"), file(B, "@@ -0,0 +1,2 @@\n+x\n+y\n"));
    assertThat(DiffLines.added(patch, Set.of(A, B)))
        .isEqualTo(Map.of(A, Set.of(2, 3), B, Set.of(1, 2)));
  }

  @Test
  void ignoresOtherFiles() {
    var patch =
        commit(file(A, "@@ -1 +1 @@\n-a\n+b\n"), file("pom.xml", "@@ -1 +1,2 @@\n x\n+y\n"));
    assertThat(DiffLines.added(patch, Set.of(A))).isEqualTo(Map.of(A, Set.of(1)));
  }

  @Test
  void handlesMultipleHunks() {
    var patch = commit(file(A, "@@ -2,2 +2,3 @@\n b\n+new\n c\n@@ -20,3 +21,2 @@\n t\n-u\n v\n"));
    assertThat(DiffLines.added(patch, Set.of(A))).isEqualTo(Map.of(A, Set.of(3)));
    var two = commit(file(A, "@@ -2,1 +2,2 @@\n b\n+n1\n@@ -9,1 +10,2 @@\n i\n+n2\n"));
    assertThat(DiffLines.added(two, Set.of(A))).isEqualTo(Map.of(A, Set.of(3, 11)));
  }

  @Test
  void deletionOnlyFileHasEmptySet() {
    var patch = commit(file(A, "@@ -1,3 +1,2 @@\n a\n-b\n c\n"));
    assertThat(DiffLines.added(patch, Set.of(A, B))).isEqualTo(Map.of(A, Set.of(), B, Set.of()));
  }

  @Test
  void noNewlineMarkerIsNotALine() {
    var patch =
        commit(
            file(
                A,
                "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n"
                    + "\\ No newline at end of file\n"));
    assertThat(DiffLines.added(patch, Set.of(A))).isEqualTo(Map.of(A, Set.of(2)));
  }

  @Test
  void laterCommitsShiftAndReplaceEarlierLines() {
    var first = commit(file(A, "@@ -5,0 +6,2 @@\n+p\n+q\n"));
    // Two lines inserted near the top, then the first added line (p, line 6) is deleted.
    var second = commit(file(A, "@@ -1,0 +2,2 @@\n+r\n+s\n@@ -6,1 +7,0 @@\n-p\n"));
    assertThat(DiffLines.added(first + second, Set.of(A))).isEqualTo(Map.of(A, Set.of(2, 3, 8)));
  }

  @Test
  void renameCarriesLinesToTheNewPath() {
    var first = commit(file("src/main/java/a/Old.java", "@@ -1 +1,2 @@\n a\n+b\n"));
    var second =
        commit(
            "diff --git a/src/main/java/a/Old.java b/"
                + A
                + "\nsimilarity index 90%\nrename from src/main/java/a/Old.java\nrename to "
                + A
                + "\nindex 1..2 100644\n--- a/src/main/java/a/Old.java\n+++ b/"
                + A
                + "\n@@ -1,2 +1,3 @@\n+z\n a\n b\n");
    assertThat(DiffLines.added(first + second, Set.of(A))).isEqualTo(Map.of(A, Set.of(1, 3)));
  }

  @Test
  void binaryPatchHasNoLines() {
    var patch =
        commit(
            "diff --git a/img.png b/img.png\nindex 1..2 100644\nGIT binary patch\n"
                + "literal 3\nKcmZ?wW&i>J0RRAJ\n\n"
                + "literal 0\nHcmV?d00001\n\n",
            file(A, "@@ -1 +1 @@\n-a\n+b\n"));
    assertThat(DiffLines.added(patch, Set.of(A, "img.png")))
        .isEqualTo(Map.of(A, Set.of(1), "img.png", Set.of()));
  }

  @Test
  void pathsWithSpacesAndQuotedPathsAreMatched() {
    var spaced = "src/main/java/a/S p.java";
    var quoted = "src/main/java/a/é.java";
    var patch =
        commit(
            "diff --git a/"
                + spaced
                + " b/"
                + spaced
                + "\nindex 1..2 100644\n--- a/"
                + spaced
                + "\t\n+++ b/"
                + spaced
                + "\t\n@@ -1 +1 @@\n-a\n+b\n",
            "diff --git \"a/src/main/java/a/\\303\\251.java\" \"b/src/main/java/a/\\303\\251.java\"\n"
                + "index 1..2 100644\n--- \"a/src/main/java/a/\\303\\251.java\"\n"
                + "+++ \"b/src/main/java/a/\\303\\251.java\"\n@@ -1 +1,2 @@\n a\n+b\n");
    assertThat(DiffLines.added(patch, Set.of(spaced, quoted)))
        .isEqualTo(Map.of(spaced, Set.of(1), quoted, Set.of(2)));
  }

  @Test
  void deletedThenRecreatedFileStartsFresh() {
    var first = commit(file(A, "@@ -1 +1,2 @@\n a\n+b\n"));
    var second =
        commit(
            "diff --git a/"
                + A
                + " b/"
                + A
                + "\ndeleted file mode 100644\nindex 1..0\n--- a/"
                + A
                + "\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-a\n-b\n",
            "");
    var third =
        commit(
            "diff --git a/"
                + A
                + " b/"
                + A
                + "\nnew file mode 100644\nindex 0..1\n--- /dev/null\n+++ b/"
                + A
                + "\n@@ -0,0 +1 @@\n+c\n");
    assertThat(DiffLines.added(first + second + third, Set.of(A))).isEqualTo(Map.of(A, Set.of(1)));
  }

  @Test
  void contentLinesThatLookLikeHeadersStayInTheHunk() {
    var patch = commit(file(A, "@@ -1 +1,3 @@\n a\n+diff --git a/x b/x\n+--- a/x\n"));
    assertThat(DiffLines.added(patch, Set.of(A))).isEqualTo(Map.of(A, Set.of(2, 3)));
  }
}
