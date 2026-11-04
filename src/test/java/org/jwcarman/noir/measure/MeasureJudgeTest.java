package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    for (var step : List.of("holdout-sources", "forbidden", "api", "changed", "deps", "pit")) {
      write(step + ".exit", "0\n");
    }
    write("holdout-sources.txt", "");
    write("forbidden.txt", "");
    write("stubs.txt", "com.example.api.Counter\n");
    write("changed.txt", "src/main/java/com/example/Calc.java\n");
  }

  private void assertIncomplete(String file) {
    assertThatThrownBy(() -> MeasureJudge.judge(a, BAR, CHANGED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("measurement incomplete: " + file);
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
  void missingBuildExitIsIncomplete() throws IOException {
    Files.delete(out.resolve("build.exit"));
    assertIncomplete("build.exit");
  }

  @Test
  void buildFailureCarriesLog() throws IOException {
    write("build.exit", "1");
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

  private static final FeedbackInput REPORTS =
      new FeedbackInput.BuildFailed("Coverage or mutation report missing or unreadable");

  private FeedbackInput shortfall() {
    return ((MeasureOutcome.Shortfall) MeasureJudge.judge(a, BAR, CHANGED)).input();
  }

  @Test
  void unparseableJacocoIsReportFailure() throws IOException {
    write("jacoco.xml", "not xml");
    assertThat(shortfall()).isEqualTo(REPORTS);
  }

  @Test
  void unparseableMutationsIsReportFailure() throws IOException {
    write("mutations.xml", "not xml");
    assertThat(shortfall()).isEqualTo(REPORTS);
  }

  @Test
  void missingMutationsWithPitExitZeroIsReportFailure() throws IOException {
    Files.delete(out.resolve("mutations.xml"));
    assertThat(shortfall()).isEqualTo(REPORTS);
  }

  @Test
  void missingJacocoIsReportFailure() throws IOException {
    Files.delete(out.resolve("jacoco.xml"));
    assertThat(shortfall()).isEqualTo(REPORTS);
  }

  @Test
  void missingPitExitIsIncomplete() throws IOException {
    Files.delete(out.resolve("pit.exit"));
    assertIncomplete("pit.exit");
  }

  @Test
  void failedPitIsBuildFailure() throws IOException {
    write("pit.exit", "1");
    write("pit.log", "a\nb\n");
    assertThat(shortfall())
        .isEqualTo(new FeedbackInput.BuildFailed("Mutation testing failed:\na\nb"));
  }

  @Test
  void failedApiStepIsContractViolation() throws IOException {
    write("api.exit", "1");
    write("api.log", "cannot find symbol\n");
    assertThat(shortfall())
        .isEqualTo(
            new FeedbackInput.ContractViolation(
                List.of(
                    "API stubs do not compile against the built classes:\ncannot find symbol")));
  }

  @Test
  void missingApiExitIsIncomplete() throws IOException {
    Files.delete(out.resolve("api.exit"));
    assertIncomplete("api.exit");
  }

  @Test
  void emptyStubJavapWhileStubsExistIsContractViolation() throws IOException {
    write("javap-stub.txt", "");
    assertThat(shortfall()).isInstanceOf(FeedbackInput.ContractViolation.class);
  }

  @Test
  void emptyStubJavapWithoutStubsIsFine() throws IOException {
    write("javap-stub.txt", "");
    write("stubs.txt", "");
    assertThat(MeasureJudge.judge(a, BAR, CHANGED)).isInstanceOf(MeasureOutcome.Passed.class);
  }

  @Test
  void missingStubsListIsIncomplete() throws IOException {
    Files.delete(out.resolve("stubs.txt"));
    assertIncomplete("stubs.txt");
  }

  @Test
  void missingChangedTxtIsIncomplete() throws IOException {
    Files.delete(out.resolve("changed.txt"));
    assertIncomplete("changed.txt");
  }

  @Test
  void oversizeChangedTxtIsIncomplete() throws IOException {
    Files.write(out.resolve("changed.txt"), new byte[(int) MeasureArtifacts.MAX_TEXT_BYTES + 1]);
    assertIncomplete("changed.txt");
  }

  @Test
  void symlinkedChangedTxtIsIncomplete() throws IOException {
    Files.delete(out.resolve("changed.txt"));
    var real = Files.writeString(out.resolve("real.txt"), "");
    Files.createSymbolicLink(out.resolve("changed.txt"), real);
    assertIncomplete("changed.txt");
  }

  @Test
  void missingHoldoutSourcesExitIsIncomplete() throws IOException {
    Files.delete(out.resolve("holdout-sources.exit"));
    assertIncomplete("holdout-sources.exit");
  }

  @Test
  void missingHoldoutSourcesListIsIncomplete() throws IOException {
    Files.delete(out.resolve("holdout-sources.txt"));
    assertIncomplete("holdout-sources.txt");
  }

  @Test
  void failedInfraStepsAreIncomplete() throws IOException {
    for (var step : List.of("holdout-sources", "changed", "deps", "forbidden")) {
      write(step + ".exit", "2");
      assertThatThrownBy(() -> MeasureJudge.judge(a, BAR, CHANGED))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(step);
      write(step + ".exit", "0");
    }
  }

  @Test
  void changedInterfaceWithoutJacocoEntryPassesWhenClassFileExists() throws IOException {
    Files.createDirectories(out.resolve("export/classes/com/example"));
    Files.writeString(out.resolve("export/classes/com/example/Api.class"), "x");
    var r = MeasureJudge.judge(a, BAR, List.of("com.example.Calc", "com.example.Api"));
    assertThat(r).isInstanceOf(MeasureOutcome.Passed.class);
  }

  @Test
  void deletedChangedClassWithoutClassFileIsSkipped() {
    var r = MeasureJudge.judge(a, BAR, List.of("com.example.Calc", "com.example.Gone"));
    assertThat(r).isInstanceOf(MeasureOutcome.Passed.class);
  }

  @Test
  void noChangedClassesPasses() throws IOException {
    write("changed.txt", "");
    Files.delete(out.resolve("jacoco.xml"));
    Files.delete(out.resolve("mutations.xml"));
    var r = MeasureJudge.judge(a, BAR, List.of());
    assertThat(r).isInstanceOf(MeasureOutcome.Passed.class);
    assertThat(r.m().lineCoverage()).isEqualTo(1.0);
  }
}
