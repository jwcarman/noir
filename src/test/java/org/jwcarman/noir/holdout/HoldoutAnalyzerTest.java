package org.jwcarman.noir.holdout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Specs;

class HoldoutAnalyzerTest {
  @TempDir Path dir;

  @Test
  void validSuitePasses() {
    source(
        "ValidTest",
        """
        import org.junit.jupiter.api.*; import com.example.api.Foo;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class ValidTest {
          @Test @Tag("R1") void a() { assertEquals(1, 1); }
          @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints = {1}) @Tag("R2") @Tag("R1") void b(int x) {}
          void helper() {}
        }\
        """);
    var r = analyze();
    assertThat(r.problems()).isEmpty();
    assertThat(r.ok()).isTrue();
    assertThat(r.testsPerRequirement())
        .containsExactlyInAnyOrderEntriesOf(Map.of("R1", 2, "R2", 1));
    assertThat(r.totalTests()).isEqualTo(2);
    var pkg = ItemId.holdoutPackage(Specs.ID);
    assertThat(r.tagsByTest())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(pkg + ".ValidTest#a", Set.of("R1"), pkg + ".ValidTest#b", Set.of("R1", "R2")));
  }

  @Test
  void repeatedAndFactoryNeedTags() {
    source(
        "T",
        "import org.junit.jupiter.api.*; class T { @RepeatedTest(2) void a() {} @TestFactory java.util.List<DynamicTest> b() { return java.util.List.of(); } @Test @Tag(\"R1\") @Tag(\"R2\") void c() {} }");
    assertThat(analyze().problems())
        .containsExactlyInAnyOrder(
            "T.java#a: test has no requirement tag", "T.java#b: test has no requirement tag");
  }

  @Test
  void classLevelTagRejected() {
    source(
        "T",
        "import org.junit.jupiter.api.*; @Tag(\"R1\") class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() {} }");
    assertThat(analyze().problems())
        .containsExactly("T.java: @Tag must be on test methods, not the class");
  }

  @Test
  void unknownTagAndUncoveredRequirement() {
    source(
        "T",
        "import org.junit.jupiter.api.*; class T { @Test @Tag(\"R9\") void a() {} @Test @Tag(\"R1\") void b() {} }");
    var r = analyze();
    assertThat(r.problems())
        .containsExactlyInAnyOrder(
            "T.java#a: tag R9 names no requirement", "R2 has no holdout tests");
    assertThat(r.ok()).isFalse();
  }

  @Test
  void wrongPackage() {
    rawSource(
        "T.java",
        "package other; import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() {} }");
    assertThat(analyze().problems())
        .containsExactly("T.java: package must be " + ItemId.holdoutPackage(Specs.valid().id()));
  }

  @Test
  void disallowedImport() {
    source(
        "T",
        "import org.assertj.core.api.Assertions; import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() {} }");
    assertThat(analyze().problems())
        .containsExactly("T.java: import not allowed: org.assertj.core.api.Assertions");
  }

  @Test
  void deniedJavaImport() {
    source(
        "T",
        "import java.net.URL; import java.util.concurrent.*; import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() {} }");
    assertThat(analyze().problems())
        .containsExactlyInAnyOrder(
            "T.java: import not allowed: java.net.URL",
            "T.java: import not allowed: java.util.concurrent.*");
  }

  @Test
  void nondeterministicCalls() {
    source(
        "T",
        "import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() throws Exception { Thread.sleep(1); java.time.Instant.now(); new java.util.Random(); new java.util.Random(42); } }");
    assertThat(analyze().problems())
        .containsExactlyInAnyOrder(
            "T.java#a: nondeterministic API not allowed: Thread.sleep(1)",
            "T.java#a: nondeterministic API not allowed: java.time.Instant.now()",
            "T.java#a: nondeterministic API not allowed: new java.util.Random()");
  }

  @Test
  void staticImportBypassCaught() {
    source(
        "T",
        "import static java.lang.Thread.sleep; import static java.time.Instant.*; import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() throws Exception { sleep(1); now(); } }");
    assertThat(analyze().problems())
        .containsExactlyInAnyOrder(
            "T.java#a: nondeterministic API not allowed: sleep(1)",
            "T.java#a: nondeterministic API not allowed: now()");
  }

  @Test
  void fullyQualifiedDeniedApisCaught() {
    source(
        "T",
        "import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() throws Exception { java.util.concurrent.Executors.newFixedThreadPool(2); new java.net.URL(\"http://x\"); Thread.startVirtualThread(() -> {}); new java.util.Date(); java.time.Year.now(); } }");
    assertThat(analyze().problems())
        .containsExactlyInAnyOrder(
            "T.java#a: nondeterministic API not allowed: java.util.concurrent.Executors.newFixedThreadPool(2)",
            "T.java#a: nondeterministic API not allowed: new java.net.URL(\"http://x\")",
            "T.java#a: nondeterministic API not allowed: Thread.startVirtualThread(() -> {\n})",
            "T.java#a: nondeterministic API not allowed: new java.util.Date()",
            "T.java#a: nondeterministic API not allowed: java.time.Year.now()");
  }

  @Test
  void fullyQualifiedDeniedTypeInDeclarationCaught() {
    source(
        "T",
        "import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R2\") void a() { java.util.concurrent.ExecutorService e = null; } }");
    assertThat(analyze().problems())
        .containsExactly(
            "T.java#a: nondeterministic API not allowed: java.util.concurrent.ExecutorService");
  }

  @Test
  void nonUtf8FileNamedAsProblem() throws IOException {
    Files.write(dir.resolve("Bad.java"), new byte[] {(byte) 0xC3, (byte) 0x28});
    assertThat(analyze().problems()).containsExactly("Bad.java: is not valid UTF-8");
  }

  @Test
  void privateOrStaticTestsRejectedAndDuplicateTagsCountOnce() {
    source(
        "T",
        "import org.junit.jupiter.api.*; class T { @Test @Tag(\"R1\") @Tag(\"R1\") void a() {} @Test @Tag(\"R2\") private void b() {} @Test @Tag(\"R2\") static void c() {} }");
    var r = analyze();
    assertThat(r.problems())
        .containsExactlyInAnyOrder(
            "T.java#b: test method must not be private or static (JUnit will not run it)",
            "T.java#c: test method must not be private or static (JUnit will not run it)",
            "R2 has no holdout tests");
    assertThat(r.testsPerRequirement()).containsEntry("R1", 1);
  }

  @Test
  void unparseableFile() {
    rawSource("T.java", "this is not java");
    assertThat(analyze().problems())
        .singleElement()
        .asString()
        .startsWith("T.java: does not parse");
  }

  @Test
  void nestedDirectoryPathsReported() {
    rawSource(
        "sub/T.java",
        "package "
            + ItemId.holdoutPackage(Specs.ID)
            + "; import org.junit.jupiter.api.*; class T { @Test void a() {} @Test @Tag(\"R1\") @Tag(\"R2\") void b() {} }");
    assertThat(analyze().problems()).containsExactly("sub/T.java#a: test has no requirement tag");
  }

  private HoldoutReport analyze() {
    return HoldoutAnalyzer.analyze(Specs.valid(), dir, Set.of("com.example.api"));
  }

  private void source(String className, String body) {
    rawSource(className + ".java", "package " + ItemId.holdoutPackage(Specs.ID) + ";\n" + body);
  }

  private void rawSource(String rel, String content) {
    try {
      var p = dir.resolve(rel);
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
