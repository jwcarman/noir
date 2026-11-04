package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.feedback.FeedbackInput;
import org.jwcarman.noir.spec.QualityBar;

class MeasureJudgeTest {
  private static final Path FIX = Path.of("src/test/resources/fixtures/measure");
  private static final QualityBar BAR = new QualityBar(0.5, 0.5, 30);
  private static final List<String> CHANGED = List.of("com.example.Calc");

  @TempDir Path out;
  MeasureArtifacts a;

  @BeforeEach
  void good() throws IOException {
    a = new MeasureArtifacts(out);
    write("build.exit", "0\n");
    Files.copy(FIX.resolve("jacoco.xml"), out.resolve("jacoco.xml"));
    Files.copy(FIX.resolve("javap-stub.txt"), out.resolve("javap-stub.txt"));
    Files.copy(FIX.resolve("javap-built.txt"), out.resolve("javap-built.txt"));
    write("mutations.xml", "<mutations/>");
  }

  private void write(String name, String content) throws IOException {
    Files.writeString(out.resolve(name), content);
  }

  @Test
  void passes() {
    var r = MeasureJudge.judge(a, BAR, CHANGED);
    assertThat(r).isInstanceOf(MeasureOutcome.Passed.class);
    assertThat(r.m().lineCoverage()).isEqualTo(0.5);
    assertThat(r.m().mutationScore()).isEqualTo(1.0);
    assertThat(r.m().maxCrap()).isEqualTo(Crap.score(5, 0.25));
    assertThat(r.m().changedClasses()).isEqualTo(CHANGED);
  }

  @Test
  void missingBuildExitIsBuildFailure() throws IOException {
    Files.delete(out.resolve("build.exit"));
    write("build.log", "boom\n");
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED);
    assertThat(s.input()).isEqualTo(new FeedbackInput.BuildFailed("boom"));
  }

  @Test
  void nonZeroBuildExitIsBuildFailure() throws IOException {
    write("build.exit", "1");
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED);
    assertThat(s.input()).isInstanceOf(FeedbackInput.BuildFailed.class);
  }

  @Test
  void holdoutSourcesAreContractViolation() throws IOException {
    write("holdout-sources.txt", "src/main/java/noir/holdout/X.java\n");
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED);
    assertThat(s.input())
        .isEqualTo(
            new FeedbackInput.ContractViolation(
                List.of(
                    "source in reserved package noir.holdout: src/main/java/noir/holdout/X.java")));
  }

  @Test
  void forbiddenOutputIsContractViolation() throws IOException {
    write("forbidden.txt", "a.class\nb.class\n");
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED);
    assertThat(s.input())
        .isEqualTo(
            new FeedbackInput.ContractViolation(
                List.of(
                    "forbidden in build output: a.class", "forbidden in build output: b.class")));
  }

  @Test
  void apiMismatchIsContractViolation() throws IOException {
    write("javap-built.txt", "public class com.example.api.Counter {\n}\n");
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED);
    assertThat(s.input()).isInstanceOf(FeedbackInput.ContractViolation.class);
    assertThat(((FeedbackInput.ContractViolation) s.input()).mismatches())
        .contains("missing: public void increment();");
  }

  @Test
  void lowCoverageAndCrapAreQualityShortfall() {
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, new QualityBar(0.9, 0.5, 5), CHANGED);
    var q = (FeedbackInput.QualityShortfall) s.input();
    assertThat(q.classes()).hasSize(1);
    assertThat(q.classes().get(0).className()).isEqualTo("com.example.Calc");
    assertThat(q.crapViolations())
        .extracting(MethodCrap::method)
        .containsExactly("pick(String,int[])");
  }

  @Test
  void crapAloneFails() {
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, new QualityBar(0.5, 0.5, 5), CHANGED);
    var q = (FeedbackInput.QualityShortfall) s.input();
    assertThat(q.crapViolations()).hasSize(1);
  }

  @Test
  void lowMutationScoreFails() throws IOException {
    write(
        "mutations.xml",
        "<mutations><mutation detected='false'><mutatedClass>com.example.Calc$Inner</mutatedClass>"
            + "</mutation></mutations>");
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED);
    var q = (FeedbackInput.QualityShortfall) s.input();
    assertThat(q.classes().get(0).mutationScore()).isEqualTo(0.0);
    assertThat(s.m().mutationScore()).isEqualTo(0.0);
  }

  @Test
  void changedClassMissingFromJacocoCountsAsUncovered() {
    var s = (MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, List.of("com.example.Ghost"));
    var q = (FeedbackInput.QualityShortfall) s.input();
    assertThat(q.classes().get(0).lineCoverage()).isEqualTo(0.0);
  }

  @Test
  void unparseableReportsCountAsMissing() throws IOException {
    write("jacoco.xml", "not xml");
    write("mutations.xml", "not xml");
    assertThat(MeasureJudge.judge(a, BAR, CHANGED)).isInstanceOf(MeasureOutcome.Shortfall.class);
  }

  @Test
  void noChangedClassesPasses() {
    var r = MeasureJudge.judge(a, BAR, List.of());
    assertThat(r).isInstanceOf(MeasureOutcome.Passed.class);
    assertThat(r.m().lineCoverage()).isEqualTo(1.0);
  }
}
