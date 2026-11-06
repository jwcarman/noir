package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Specs;

class ExaminerPromptTest {
  @Test
  void userPromptCarriesSpecPackageAndProblems() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(), List.of(), List.of("holdout test is nondeterministic: X#y"));
    assertThat(p)
        .contains("# Add a bounded cache")
        .contains("## Holdout package")
        .contains(ItemId.holdoutPackage(Specs.ID))
        .contains("## Problems with your previous attempt")
        .contains("- holdout test is nondeterministic: X#y");
  }

  @Test
  void firstAttemptHasNoProblemsSection() {
    assertThat(ExaminerPrompt.user(Specs.valid(), List.of(), List.of()))
        .doesNotContain("Problems with your previous attempt");
  }

  @Test
  void multiLineProblemsStayReadable() {
    var p =
        ExaminerPrompt.user(
            Specs.valid(), List.of(), List.of("API stubs do not compile:\nline1\nline2"));
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
}
