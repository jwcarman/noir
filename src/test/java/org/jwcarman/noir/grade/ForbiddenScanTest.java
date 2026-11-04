package org.jwcarman.noir.grade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.ProcessCommandRunner;

/** Runs the real {@code ForbiddenScan.java} shipped in the grader image, on the host JDK. */
class ForbiddenScanTest {
  static final String CLASS_PATH_MANIFEST =
      "Manifest-Version: 1.0\r\nClass-Path: other.jar\r\n\r\n";

  @TempDir Path tmp;
  Path classes;
  Path deps;
  Path result;

  @BeforeEach
  void dirs() throws IOException {
    classes = Files.createDirectories(tmp.resolve("classes"));
    deps = Files.createDirectories(tmp.resolve("deps"));
    result = tmp.resolve("forbidden.txt");
  }

  List<String> scan() throws Exception {
    var script =
        Path.of(ForbiddenScanTest.class.getResource("/docker/grader/ForbiddenScan.java").toURI());
    var r =
        new ProcessCommandRunner()
            .run(
                Command.of(
                    "java",
                    script.toString(),
                    classes.toString(),
                    deps.toString(),
                    result.toString()));
    assertThat(r.ok()).as(r.tail(20)).isTrue();
    return Files.readAllLines(result);
  }

  void jar(String name, Map<String, byte[]> entries) throws IOException {
    try (OutputStream out = Files.newOutputStream(deps.resolve(name));
        var zip = new ZipOutputStream(out)) {
      for (var e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue());
        zip.closeEntry();
      }
    }
  }

  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  void cleanTreeHasNoProblems() throws Exception {
    Files.createDirectories(classes.resolve("com/example"));
    Files.writeString(classes.resolve("com/example/A.class"), "x");
    jar("ok-1.0.jar", Map.of("a/B.class", bytes("x")));
    assertThat(scan()).isEmpty();
  }

  @Test
  void classesHoldoutLookalikeServicesAndProperties() throws Exception {
    Files.createDirectories(classes.resolve("noir/holdout"));
    Files.writeString(classes.resolve("noir/holdout/T.class"), "x");
    Files.createDirectories(classes.resolve("META-INF/services"));
    Files.writeString(classes.resolve("META-INF/services/org.junit.Foo"), "x");
    Files.writeString(classes.resolve("junit-platform.properties"), "x");
    assertThat(scan())
        .anyMatch(l -> l.contains("noir/holdout"))
        .anyMatch(l -> l.contains("META-INF/services/org.junit.Foo"))
        .anyMatch(l -> l.contains("junit-platform.properties"));
  }

  @Test
  void caseVariantsInClassesAreFlagged() throws Exception {
    Files.createDirectories(classes.resolve("NOIR/Holdout"));
    Files.writeString(classes.resolve("NOIR/Holdout/T.class"), "x");
    Files.writeString(classes.resolve("JUnit-Platform.properties"), "x");
    assertThat(scan())
        .anyMatch(l -> l.contains("Holdout"))
        .anyMatch(l -> l.contains("JUnit-Platform"));
  }

  @Test
  void symlinkInClassesIsFlagged() throws Exception {
    Files.createSymbolicLink(classes.resolve("link"), tmp);
    assertThat(scan()).anyMatch(l -> l.contains("link"));
  }

  @Test
  void jarEntriesAreFlagged() throws Exception {
    jar(
        "bad.jar",
        Map.of(
            "noir/holdout/T.class", bytes("x"),
            "META-INF/services/org.junit.platform.Foo", bytes("x"),
            "junit-platform.properties", bytes("x")));
    assertThat(scan())
        .anyMatch(l -> l.contains("noir/holdout/T.class"))
        .anyMatch(l -> l.contains("META-INF/services/org.junit.platform.Foo"))
        .anyMatch(l -> l.contains("junit-platform.properties"));
  }

  @Test
  void classPathManifestIsFlagged() throws Exception {
    jar("cp.jar", Map.of("META-INF/MANIFEST.MF", bytes(CLASS_PATH_MANIFEST)));
    assertThat(scan()).anyMatch(l -> l.contains("cp.jar") && l.contains("Class-Path"));
  }

  @Test
  void lowercaseManifestNameWithClassPathIsFlagged() throws Exception {
    jar("lower.jar", Map.of("meta-inf/manifest.mf", bytes(CLASS_PATH_MANIFEST)));
    assertThat(scan()).anyMatch(l -> l.contains("lower.jar"));
  }

  @Test
  void carriageReturnOnlyManifestWithClassPathIsFlagged() throws Exception {
    jar(
        "cr.jar",
        Map.of("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\rClass-Path: other.jar\r\r")));
    assertThat(scan()).anyMatch(l -> l.contains("cr.jar"));
  }

  @Test
  void garbageJarIsFlagged() throws Exception {
    Files.writeString(deps.resolve("garbage.jar"), "not a zip");
    assertThat(scan()).anyMatch(l -> l.contains("garbage.jar"));
  }

  @Test
  void badDependencyNamesAreFlagged() throws Exception {
    jar("a.jar", Map.of("a/B.class", bytes("x")));
    jar("[ab].jar", Map.of("a/B.class", bytes("x")));
    Files.writeString(deps.resolve("notes.txt"), "x");
    Files.createDirectories(deps.resolve("sub"));
    Files.createSymbolicLink(deps.resolve("link.jar"), deps.resolve("a.jar"));
    assertThat(scan())
        .anyMatch(l -> l.contains("[ab].jar"))
        .anyMatch(l -> l.contains("notes.txt"))
        .anyMatch(l -> l.contains("sub"))
        .anyMatch(l -> l.contains("link.jar"))
        .noneMatch(l -> l.contains("/a.jar"));
  }

  static final String MULTI_RELEASE_MANIFEST =
      "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n";

  @Test
  void multiReleaseVersionedJunitPropertiesIsFlagged() throws Exception {
    jar(
        "mr.jar",
        Map.of(
            "META-INF/MANIFEST.MF", bytes(MULTI_RELEASE_MANIFEST),
            "META-INF/versions/9/junit-platform.properties",
                bytes("junit.jupiter.execution.timeout.mode=disabled\n")));
    assertThat(scan())
        .anyMatch(l -> l.contains("mr.jar") && l.contains("junit-platform.properties"));
  }

  @Test
  void multiReleaseVersionedHoldoutLookalikeIsFlagged() throws Exception {
    jar(
        "mr.jar",
        Map.of(
            "META-INF/MANIFEST.MF", bytes(MULTI_RELEASE_MANIFEST),
            "META-INF/versions/11/noir/holdout/X.class", bytes("x")));
    assertThat(scan()).anyMatch(l -> l.contains("mr.jar") && l.contains("noir/holdout/X.class"));
  }

  @Test
  void versionedPrefixIsStrippedCaseInsensitivelyInJarsAndClasses() throws Exception {
    jar("lower.jar", Map.of("meta-inf/Versions/17/junit-platform.properties", bytes("x")));
    Files.createDirectories(classes.resolve("META-INF/versions/9/noir/holdout"));
    Files.writeString(classes.resolve("META-INF/versions/9/noir/holdout/T.class"), "x");
    assertThat(scan())
        .anyMatch(l -> l.contains("lower.jar") && l.contains("junit-platform.properties"))
        .anyMatch(l -> l.contains("META-INF/versions/9/noir/holdout"));
  }

  @Test
  void versionedOrdinaryClassesAreClean() throws Exception {
    jar(
        "mr-ok.jar",
        Map.of(
            "META-INF/MANIFEST.MF", bytes(MULTI_RELEASE_MANIFEST),
            "META-INF/versions/9/a/B.class", bytes("x")));
    assertThat(scan()).isEmpty();
  }
}
