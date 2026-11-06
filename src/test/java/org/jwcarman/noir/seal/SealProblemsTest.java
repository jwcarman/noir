package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SealProblemsTest {
  static List<String> summary(String... problems) {
    return SealProblems.publicSummary(List.of(problems));
  }

  @Test
  void stubsDoNotCompile() {
    assertThat(summary("API stubs do not compile:\n[ERROR] Foo.java:[3,1] boom"))
        .containsExactly("the API stubs do not compile");
  }

  @Test
  void holdoutDoesNotCompile() {
    assertThat(summary("holdout tests do not compile against the stubs:\n[ERROR] T.java secret"))
        .containsExactly("the hidden tests do not compile against the stubs");
  }

  @Test
  void noTestsFound() {
    assertThat(summary("no holdout tests were discovered"))
        .containsExactly("no hidden tests were found");
  }

  @Test
  void vacuousTestsAreCounted() {
    assertThat(summary("holdout test passes against the stubs (vacuous): nr.Foo#bar"))
        .containsExactly("1 hidden test passed against the stubs");
    assertThat(
            summary(
                "holdout test passes against the stubs (vacuous): nr.Foo#bar",
                "holdout test passes against the stubs (vacuous): nr.Foo#baz"))
        .containsExactly("2 hidden tests passed against the stubs");
  }

  @Test
  void referenceDoesNotCompile() {
    assertThat(summary("reference implementation does not compile:\n[ERROR] Impl.java"))
        .containsExactly("the reference implementation does not compile");
  }

  @Test
  void referenceFailures() {
    assertThat(
            summary(
                "holdout test fails against the reference implementation: nr.Foo#bar",
                "holdout test is nondeterministic: nr.Foo#baz"))
        .containsExactly("the reference implementation failed the hidden tests");
  }

  @Test
  void mutationScore() {
    assertThat(
            summary(
                "holdout tests are not discriminating enough: mutation score 0.10 < 0.60"
                    + " (1 of 10 mutants killed)",
                "mutation testing produced no mutants:\nlog tail"))
        .containsExactly("mutation score below the required threshold");
  }

  @Test
  void structuralRules() {
    assertThat(
            summary(
                "noir/holdout/T.java#a: test has no requirement tag",
                "noir/holdout/T.java: import not allowed: java.net.URL",
                "noir/holdout/T.java#a: nondeterministic API not allowed: Instant.now()",
                "R2 has no holdout tests",
                "holdout test is skipped (disabled or a failed assumption): nr.Foo#bar",
                "Examiner output must not contain symlinks: holdout/x.java",
                "Sealed file is not valid UTF-8: holdout/x.java"))
        .containsExactly("the hidden tests did not meet noir's structural rules");
  }

  @Test
  void sealCheckDidNotComplete() {
    assertThat(
            summary(
                "seal check did not complete step stub-run:\nlog",
                "seal check failed at step clone:\nlog"))
        .containsExactly("a seal check did not complete");
  }

  @Test
  void repositoryShapeProblemsPassThrough() {
    assertThat(
            summary(
                "pom.xml not found at repository root",
                "multi-module Maven builds are not supported",
                "maven.compiler.release must be from 17 to 25 (found 11)",
                "maven.compiler.release must be from 17 to 25 (found unset)"))
        .containsExactly(
            "pom.xml not found at repository root",
            "multi-module Maven builds are not supported",
            "maven.compiler.release must be from 17 to 25 (found 11)",
            "maven.compiler.release must be from 17 to 25 (found unset)");
  }

  @Test
  void lookalikesOfPassthroughAreNotCopied() {
    assertThat(summary("maven.compiler.release must be from 17 to 25 (found nr.Foo#bar)"))
        .containsExactly("other check failures");
  }

  @Test
  void unmatchedIsOtherCheckFailures() {
    assertThat(summary("something new: nr.Foo#bar", "another thing"))
        .containsExactly("other check failures");
  }

  @Test
  void phrasesFollowTheFixedOrder() {
    assertThat(
            summary(
                "zzz",
                "a seal check did not complete step x",
                "seal check did not complete step pit:\n",
                "holdout test passes against the stubs (vacuous): a",
                "API stubs do not compile:\n",
                "pom.xml not found at repository root"))
        .containsExactly(
            "pom.xml not found at repository root",
            "the API stubs do not compile",
            "1 hidden test passed against the stubs",
            "a seal check did not complete",
            "other check failures");
  }

  @Test
  void neverCopiesTestNamesSourceOrLogs() {
    var problems =
        List.of(
            "API stubs do not compile:\nLOGMARK1",
            "holdout tests do not compile against the stubs:\nLOGMARK2 class SRCMARK {}",
            "holdout test passes against the stubs (vacuous): nr.TESTMARK#a",
            "reference implementation does not compile:\nLOGMARK3",
            "holdout test fails against the reference implementation: nr.TESTMARK#b",
            "holdout test is nondeterministic: nr.TESTMARK#c",
            "mutation testing produced no mutants:\nLOGMARK4",
            "noir/holdout/TESTMARK.java#d: test has no requirement tag",
            "holdout test is skipped (disabled or a failed assumption): nr.TESTMARK#e",
            "seal check did not complete step stub-run:\nLOGMARK5",
            "TESTMARK unknown");
    var text = String.join("\n", SealProblems.publicSummary(problems));
    assertThat(text)
        .doesNotContain("MARK")
        .doesNotContain("nr.")
        .doesNotContain("#")
        .doesNotContain(".java")
        .doesNotContain("holdout");
  }

  @Test
  void existingTestProblemIsPublicWithClassName() {
    assertThat(
            summary(
                "existing test fails with the reference: com.example.api.LegacyCounterTest#incrementAddsTwo",
                "existing test fails with the reference: com.example.api.LegacyCounterTest#resetKeeps",
                "existing test fails with the reference: com.example.api.OtherTest#a"))
        .containsExactly(
            "existing tests fail with the reference implementation:"
                + " `com.example.api.LegacyCounterTest`, `com.example.api.OtherTest`")
        .noneMatch(
            p -> p.contains("incrementAddsTwo") || p.contains("resetKeeps") || p.contains("#"));
  }

  @Test
  void existingTestsThatCouldNotRunIsAFixedPhrase() {
    assertThat(summary("existing tests could not run with the reference:\n[ERROR] Secret.java"))
        .containsExactly("the existing tests could not run with the reference implementation");
  }

  @Test
  void existingTestProblemWithUnexpectedShapeIsNotPassedThrough() {
    assertThat(summary("existing test fails with the reference: has spaces#m"))
        .containsExactly("other check failures");
    assertThat(summary("existing test fails with the reference: noir.holdout.nr_x.T#m"))
        .containsExactly("other check failures");
    assertThat(summary("existing test fails with the reference: a.B\nsecret#m"))
        .containsExactly("other check failures");
  }
}
