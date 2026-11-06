package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.noir.seal.SealArtifactsFixture.EMPTY_INVENTORY;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Specs;

class ExaminerPromptTest {
  @Test
  void userPromptCarriesSpecPackageAndProblems() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            EMPTY_INVENTORY,
            List.of("holdout test is nondeterministic: X#y"));
    assertThat(p)
        .contains("# Add a bounded cache")
        .contains("## Holdout package")
        .contains(ItemId.holdoutPackage(Specs.ID))
        .contains("## Problems with your previous attempt")
        .contains("- holdout test is nondeterministic: X#y");
  }

  @Test
  void firstAttemptHasNoProblemsSection() {
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of(), EMPTY_INVENTORY, List.of()))
        .doesNotContain("Problems with your previous attempt");
  }

  @Test
  void multiLineProblemsStayReadable() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            EMPTY_INVENTORY,
            List.of("API stubs do not compile:\nline1\nline2"));
    assertThat(p).contains("- API stubs do not compile:\n  ```\n  line1\n  line2\n  ```");
  }

  @Test
  void systemPromptStatesRules() {
    assertThat(ExaminerPrompt.system().replaceAll("\\s+", " "))
        .contains("/work/out/holdout")
        .contains("/work/out/reference")
        .contains("@Tag")
        .contains("org.junit.jupiter")
        .contains("the libraries listed under \"Test libraries\"")
        .contains("UnsupportedOperationException")
        .contains("Thread.sleep")
        .contains("never see");
  }

  @Test
  void systemPromptNamesEveryPackageTheAnalyzerDenies() {
    assertThat(HoldoutAnalyzer.deniedPackages()).isNotEmpty();
    for (var pkg : HoldoutAnalyzer.deniedPackages()) {
      assertThat(ExaminerPrompt.system()).contains("`" + pkg + "`");
    }
  }

  @Test
  void systemPromptNamesEveryBannedNondeterministicApi() {
    assertThat(ExaminerPrompt.system())
        .contains("System.currentTimeMillis")
        .contains("System.nanoTime")
        .contains("Math.random")
        .contains("new Random()")
        .contains("new Date()")
        .contains("new Thread")
        .contains(".now()");
  }

  @Test
  void systemPromptSaysHowToReplaceAForbiddenTypeUsedBySpecExamples() {
    assertThat(ExaminerPrompt.system()).contains("AtomicLong").contains("long[]");
  }

  static final String OLD = "src/main/java/com/example/Old.java";
  static final String NEW = "src/main/java/com/example/New.java";

  @Test
  void userPromptCarriesCurrentSourceForModifyTargets() {
    var spec = Specs.withModifyAndTests();
    var fragment = "package com.example;\n\npublic class Old {\n  public int added() {}\n}\n";
    var complete =
        "package com.example;\n\n/** ```x``` */\npublic class Old {\n  public void kept() {}\n\n"
            + "  public int added() {\n    throw new UnsupportedOperationException();\n  }\n}\n";
    var newStub = new StubSource(NEW, "package com.example;\n\npublic class New {}\n");
    var p =
        ExaminerPrompt.user(
            spec,
            List.of(newStub, new StubSource(OLD, fragment)),
            List.of(newStub, new StubSource(OLD, complete)),
            EMPTY_INVENTORY,
            List.of());
    assertThat(p)
        .containsSubsequence(
            "### " + OLD + " (modify)",
            "public int added() {}",
            "## Holdout package",
            "## Current source\n\n### " + OLD + "\n\n````java\n" + complete + "````\n")
        .doesNotContain("### " + NEW + "\n\n````");
    assertThat(p.substring(p.indexOf("## Current source"))).doesNotContain(NEW);
  }

  @Test
  void userPromptOmitsCurrentSourceWithoutModifyTargets() {
    var stub = new StubSource(NEW, "package com.example;\n\npublic class New {}\n");
    assertThat(
            ExaminerPrompt.user(
                Specs.valid(), List.of(stub), List.of(stub), EMPTY_INVENTORY, List.of()))
        .doesNotContain("## Current source");
  }

  @Test
  void systemPromptStatesModifyRules() {
    assertThat(ExaminerPrompt.system().replaceAll("\\s+", " "))
        .contains("keep every public member")
        .contains("Current source")
        .contains(
            "When the spec modifies an existing file, the \"Current source\" section shows that"
                + " file as it is today, with added members stubbed. Your reference"
                + " implementation of that file is the complete file: keep every public member,"
                + " and change only what the requirements say.");
  }

  /**
   * A changed member's stub keeps today's body, so "fails against the stubs" means "fails against
   * today's behavior": a test of behavior the change keeps would be rejected as vacuous.
   */
  @Test
  void systemPromptSaysChangedMembersRunTodaysCode() {
    var prompt = ExaminerPrompt.system().replaceAll("\\s+", " ");
    assertThat(prompt)
        .contains(
            "A changed member keeps today's body in the stubs, so every test must fail against"
                + " today's behavior.")
        .contains(
            "Behavior the change keeps, such as a round trip that already works, may be asserted"
                + " only inside a test that also checks changed behavior.")
        .doesNotContain("whose method bodies throw `UnsupportedOperationException`. A test");
  }

  @Test
  void userPromptShowsListedTestsUnderCurrentSource() {
    var spec = Specs.withModifyAndTests();
    var stub = new StubSource(OLD, "package com.example;\n\npublic class Old {}\n");
    var tests = new java.util.LinkedHashMap<String, String>();
    tests.put(spec.testsToUpdate().get(0), "class OldTest {}\n");
    tests.put(spec.testsToUpdate().get(1), "class AaTest {}\n");
    var p = ExaminerPrompt.user(spec, List.of(stub), List.of(stub), withTests(tests), List.of());
    assertThat(p)
        .containsSubsequence(
            "## Current source\n\n### " + OLD + "\n",
            "### "
                + spec.testsToUpdate().get(0)
                + " (test to update)\n\n```java\nclass OldTest {}\n```\n",
            "### "
                + spec.testsToUpdate().get(1)
                + " (test to update)\n\n```java\nclass AaTest {}\n```\n");
  }

  @Test
  void userPromptShowsListedTestsWithoutModifyTargets() {
    var spec = Specs.valid().withTestsToUpdate(List.of("src/test/java/com/example/FooTest.java"));
    var p =
        ExaminerPrompt.user(
            spec,
            List.of(),
            List.of(),
            withTests(
                java.util.Map.of("src/test/java/com/example/FooTest.java", "class FooTest {}\n")),
            List.of());
    assertThat(p)
        .contains(
            "## Current source\n\n### src/test/java/com/example/FooTest.java (test to update)\n\n"
                + "```java\nclass FooTest {}\n```\n");
  }

  @Test
  void systemPromptSaysListedTestsAreUnderCurrentSource() {
    assertThat(ExaminerPrompt.system().replaceAll("\\s+", " "))
        .contains("Their current source is under Current source, marked (test to update).");
  }

  private static RepoInventory withTests(java.util.Map<String, String> tests) {
    return new RepoInventory(
        List.of(), java.util.Set.of(), java.util.Set.of(), java.util.Optional.empty(), "", tests);
  }

  private static RepoInventory inventory(
      List<String> libraries, java.util.Optional<String> application, String api) {
    return new RepoInventory(
        libraries, java.util.Set.of(), java.util.Set.of(), application, api, java.util.Map.of());
  }

  @Test
  void userPromptListsTestLibraries() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            inventory(
                List.of("org.assertj:assertj-core:3.26.0", "org.mockito:mockito-core:5.0.0"),
                java.util.Optional.empty(),
                ""),
            List.of());
    assertThat(p)
        .contains(
            "## Test libraries\n\n- org.assertj:assertj-core:3.26.0\n"
                + "- org.mockito:mockito-core:5.0.0\n")
        .doesNotContain("JUnit and the JDK only");
  }

  @Test
  void userPromptWithoutLibrariesSaysJUnitAndTheJdkOnly() {
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of(), EMPTY_INVENTORY, List.of()))
        .contains("## Test libraries\n\nJUnit and the JDK only\n");
  }

  @Test
  void userPromptNamesTheApplicationClass() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            inventory(List.of(), java.util.Optional.of("com.example.App"), ""),
            List.of());
    assertThat(p)
        .contains("## Spring\n\nThe application class is `com.example.App`")
        .contains("@SpringBootTest(classes = com.example.App.class)")
        .contains("@ContextConfiguration(classes = com.example.App.class)");
  }

  @Test
  void userPromptOmitsApplicationClassWithoutSpringBoot() {
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of(), EMPTY_INVENTORY, List.of()))
        .doesNotContain("Spring");
  }

  @Test
  void userPromptCarriesRepositoryApi() {
    var api = "### src/main/java/com/example/Foo.java\n\n```java\npublic class Foo {}\n```\n";
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            inventory(List.of(), java.util.Optional.empty(), api),
            List.of());
    assertThat(p).contains("## Repository API\n\n" + api);
  }

  @Test
  void userPromptOmitsRepositoryApiWhenEmpty() {
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of(), EMPTY_INVENTORY, List.of()))
        .doesNotContain("## Repository API");
  }

  @Test
  void repositorySectionsFollowCurrentSource() {
    var spec = Specs.withModifyAndTests();
    var stub = new StubSource(OLD, "package com.example;\n\npublic class Old {}\n");
    var p =
        ExaminerPrompt.user(
            spec,
            List.of(stub),
            List.of(stub),
            inventory(List.of("a:b:1"), java.util.Optional.of("com.example.App"), "### x\n"),
            List.of("boom"));
    assertThat(p)
        .containsSubsequence(
            "## Current source",
            "## Test libraries",
            "## Spring",
            "## Repository API",
            "## Problems with your previous attempt");
  }

  @Test
  void userPromptStatesSpringRulesForSpringTestLibraries() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            inventory(
                List.of("org.springframework:spring-test:6.1.0"), java.util.Optional.empty(), ""),
            List.of());
    assertThat(p)
        .contains("## Spring")
        .contains("Prefer test slices")
        .contains("MockMvc")
        .contains("@MockitoBean")
        .doesNotContain("The application class is");
  }

  @Test
  void plainPromptsMentionNoSpring() {
    var user =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            inventory(List.of("org.assertj:assertj-core:3.26.0"), java.util.Optional.empty(), ""),
            List.of());
    assertThat(user).doesNotContain("Spring");
    assertThat(ExaminerPrompt.system()).doesNotContain("Spring");
  }

  @Test
  void systemPromptStatesRealServerAndTestsToUpdateRules() {
    var prompt = ExaminerPrompt.system().replaceAll("\\s+", " ");
    assertThat(prompt)
        .contains("must not start a real server")
        .contains("update a listed test helper")
        .contains("The builder never sees these files");
  }
}
