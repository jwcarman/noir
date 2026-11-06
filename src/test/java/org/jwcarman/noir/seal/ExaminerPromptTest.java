package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

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
            Specs.valid(), List.of(), List.of(), List.of("holdout test is nondeterministic: X#y"));
    assertThat(p)
        .contains("# Add a bounded cache")
        .contains("## Holdout package")
        .contains(ItemId.holdoutPackage(Specs.ID))
        .contains("## Problems with your previous attempt")
        .contains("- holdout test is nondeterministic: X#y");
  }

  @Test
  void firstAttemptHasNoProblemsSection() {
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of(), List.of()))
        .doesNotContain("Problems with your previous attempt");
  }

  @Test
  void multiLineProblemsStayReadable() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(),
            List.of(),
            List.of(),
            List.of("API stubs do not compile:\nline1\nline2"));
    assertThat(p).contains("- API stubs do not compile:\n  ```\n  line1\n  line2\n  ```");
  }

  @Test
  void systemPromptStatesRules() {
    assertThat(ExaminerPrompt.system())
        .contains("/work/out/holdout")
        .contains("/work/out/reference")
        .contains("@Tag")
        .contains("org.junit.jupiter")
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
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(stub), List.of(stub), List.of()))
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
}
