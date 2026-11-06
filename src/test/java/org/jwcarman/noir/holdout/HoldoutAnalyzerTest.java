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

  /** The workarounds the Examiner prompt recommends must themselves pass the analyzer. */
  @Test
  void examinerPromptWorkaroundsPass() {
    source(
        "ClockTest",
        """
        import org.junit.jupiter.api.*; import java.util.function.LongSupplier;
        import java.time.Instant; import java.util.Random;
        class ClockTest {
          @Test @Tag("R1") @Tag("R2") void a() {
            long[] now = {0};
            LongSupplier clock = () -> now[0];
            now[0] += 5;
            Instant t = Instant.parse("2026-01-01T00:00:00Z");
            int r = new Random(42).nextInt();
          }
        }\
        """);
    assertThat(analyze().problems()).isEmpty();
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

  private static final String SPRING_DEP = "org.springframework.boot.test.autoconfigure.orm.jpa";

  private HoldoutReport analyzeWith(Set<String> subpackages, Set<String> exact) {
    return HoldoutAnalyzer.analyze(Specs.valid(), dir, subpackages, exact);
  }

  private void springTest(String imports, String annotation) {
    source(
        "SpringT",
        imports
            + "\nimport org.junit.jupiter.api.*;\n"
            + annotation
            + "\nclass SpringT { @Test @Tag(\"R1\") void a() {} @Test @Tag(\"R2\") void b() {} }");
  }

  @Test
  void springImportsAllowedWhenInTheInventory() {
    springTest("import " + SPRING_DEP + ".DataJpaTest;", "@DataJpaTest");
    assertThat(analyzeWith(Set.of(), Set.of(SPRING_DEP)).problems()).isEmpty();
    assertThat(analyzeWith(Set.of(), Set.of()).problems())
        .containsExactly("SpringT.java: import not allowed: " + SPRING_DEP + ".DataJpaTest");
  }

  @Test
  void dependencyPackagesAreExactMatchOnly() {
    springTest("import " + SPRING_DEP + ".internal.Hidden;", "");
    assertThat(analyzeWith(Set.of(), Set.of(SPRING_DEP)).problems()).hasSize(1);
  }

  @Test
  void staticNestedAndWildcardImportsResolveToTheirPackage() {
    springTest(
        "import static "
            + SPRING_DEP
            + ".Foo.bar;\nimport "
            + SPRING_DEP
            + ".Foo.Inner;\nimport "
            + SPRING_DEP
            + ".*;",
        "");
    assertThat(analyzeWith(Set.of(), Set.of(SPRING_DEP)).problems()).isEmpty();
  }

  @Test
  void mainPackagesAdmitSubpackages() {
    springTest("import com.acme.app.sub.Thing;", "");
    assertThat(analyzeWith(Set.of("com.acme.app"), Set.of()).problems()).isEmpty();
  }

  @Test
  void deniedPackagesStayDenied() {
    springTest("import java.net.URI;", "");
    assertThat(analyzeWith(Set.of("java.net"), Set.of("java.net")).problems())
        .contains("SpringT.java: import not allowed: java.net.URI");
  }

  @Test
  void dependencyPackagesNeverWidenJunitPlatform() {
    springTest("import org.junit.platform.launcher.Launcher;", "");
    assertThat(analyzeWith(Set.of(), Set.of("org.junit.platform.launcher")).problems())
        .containsExactly("SpringT.java: import not allowed: org.junit.platform.launcher.Launcher");
  }

  @Test
  void realServerRejected() {
    for (var v : new String[] {"RANDOM_PORT", "DEFINED_PORT"}) {
      springTest(
          "import org.springframework.boot.test.context.SpringBootTest;",
          "@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment." + v + ")");
      assertThat(analyzeWith(Set.of(), Set.of("org.springframework.boot.test.context")).problems())
          .containsExactly("SpringT.java: a real server is not allowed: webEnvironment = " + v);
    }
    springTest(
        "import org.springframework.boot.test.context.SpringBootTest;\nimport org.springframework.boot.test.context.SpringBootTest.WebEnvironment;",
        "@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)");
    assertThat(analyzeWith(Set.of(), Set.of("org.springframework.boot.test.context")).problems())
        .containsExactly(
            "SpringT.java: a real server is not allowed: webEnvironment = RANDOM_PORT");
  }

  @Test
  void mockMvcAllowed() {
    springTest(
        "import org.springframework.boot.test.context.SpringBootTest;\nimport org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;\nimport org.springframework.test.web.servlet.MockMvc;\nimport static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;",
        "@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK) @AutoConfigureMockMvc");
    assertThat(
            analyzeWith(
                    Set.of(),
                    Set.of(
                        "org.springframework.boot.test.context",
                        "org.springframework.boot.test.autoconfigure.web.servlet",
                        "org.springframework.test.web.servlet",
                        "org.springframework.test.web.servlet.request"))
                .problems())
        .isEmpty();
  }

  /** Java 25 syntax (an unnamed variable) parses; noir supports releases up to 25. */
  @Test
  void java25SyntaxParses() {
    source(
        "Java25Test",
        """
        import org.junit.jupiter.api.Tag;
        import org.junit.jupiter.api.Test;

        class Java25Test {
          /** R1. */
          @Test
          @Tag("R1")
          void counts() {
            int n = 0;
            for (var _ : new int[] {1, 2}) {
              n++;
            }
            org.junit.jupiter.api.Assertions.assertEquals(2, n);
          }
        }
        """);
    assertThat(analyze().problems()).noneMatch(p -> p.contains("does not parse"));
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
