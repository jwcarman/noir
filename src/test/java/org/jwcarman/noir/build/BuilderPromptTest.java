package org.jwcarman.noir.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Specs;

class BuilderPromptTest {
  static final List<StubSource> STUBS =
      List.of(
          new StubSource(
              "src/main/java/com/example/api/Foo.java",
              "package com.example.api;\n\npublic class Foo {}\n"));

  @Test
  void userPromptIsSpecMd() {
    var p = BuilderPrompt.user(Specs.valid(), STUBS, Optional.empty());
    assertThat(p).isEqualTo(SpecRenderer.specMd(Specs.valid(), STUBS));
    assertThat(p).doesNotContain("# FEEDBACK.md");
  }

  @Test
  void userPromptAppendsFeedback() {
    var p = BuilderPrompt.user(Specs.valid(), STUBS, Optional.of("Coverage too low on Foo."));
    assertThat(p)
        .startsWith(SpecRenderer.specMd(Specs.valid(), STUBS))
        .endsWith("\n\n# FEEDBACK.md\n\nCoverage too low on Foo.");
  }

  @Test
  void systemPromptStatesRules() {
    assertThat(BuilderPrompt.system())
        .contains("/work/repo")
        .contains("noir.holdout")
        .contains("mvn -B verify")
        .contains("CRAP")
        .contains("SPEC.md")
        .contains("FEEDBACK.md")
        .contains("JUnit Jupiter")
        .contains("hidden acceptance tests");
  }

  @Test
  void systemPromptStatesLockRule() {
    assertThat(BuilderPrompt.system().replaceAll("\\s+", " "))
        .contains("Files you may change")
        .contains(
            "Change only the files listed under \"Files you may change\" in SPEC.md, plus any new"
                + " files. noir rejects an attempt that modifies, deletes or renames any other"
                + " existing file.");
  }

  @Test
  void systemPromptExplainsSpecProblemReports() {
    assertThat(BuilderPrompt.system())
        .contains("## When the spec cannot be satisfied")
        .contains("`/work/SPEC-PROBLEMS.json`")
        .contains("never disable, skip or weaken a test you may not change");
  }
}
