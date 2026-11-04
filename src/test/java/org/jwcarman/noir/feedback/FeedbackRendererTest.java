package org.jwcarman.noir.feedback;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.noir.feedback.TestOutcome.Result.FAILED;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.measure.MethodCrap;
import org.jwcarman.noir.spec.QualityBar;

class FeedbackRendererTest {
  @Test
  void behaviorFailureLeaksNothingFromTests() {
    var outcomes =
        List.of(
            new TestOutcome(
                "SecretNameTest#blankKeyThrows",
                Set.of("R1"),
                FAILED,
                "expected IllegalArgumentException"));
    var md =
        FeedbackRenderer.render(
            2,
            new FeedbackInput.BehaviorFailure(GradeAggregator.aggregate(outcomes, List.of("R1"))));
    assertThat(md)
        .startsWith("# Feedback on attempt 2: BEHAVIOR_FAILURE")
        .contains("R1: 0/1 passed")
        .contains(
            "Hidden acceptance tests check only the requirements in SPEC.md. Re-read the failing requirements and their examples.")
        .doesNotContain("SecretNameTest")
        .doesNotContain("blankKeyThrows")
        .doesNotContain("IllegalArgumentException");
  }

  @Test
  void qualityShortfallTable() {
    var md =
        FeedbackRenderer.render(
            1,
            new FeedbackInput.QualityShortfall(
                new QualityBar(0.8, 0.7, 30),
                List.of(new ClassQuality("com.example.Foo", 0.6234, 0.5)),
                List.of()));
    assertThat(md)
        .startsWith("# Feedback on attempt 1: QUALITY_SHORTFALL")
        .contains("Required: line coverage ≥ 80.0%, mutation score ≥ 70.0%")
        .contains("| Class | Line coverage | Mutation score |")
        .contains("| com.example.Foo | 62.3% | 50.0% |");
  }

  @Test
  void qualityShortfallListsCrapViolations() {
    var md =
        FeedbackRenderer.render(
            1,
            new FeedbackInput.QualityShortfall(
                new QualityBar(0.8, 0.7, 30),
                List.of(new ClassQuality("com.example.Foo", 0.9, 0.9)),
                List.of(new MethodCrap("com.example.Foo", "add(int,int)", 10, 0.5, 22.5))));
    assertThat(md)
        .contains("Methods over the CRAP limit (max 30):")
        .contains("| Method | CRAP | Complexity | Line coverage |")
        .contains("| com.example.Foo.add(int,int) | 22.5 | 10 | 50.0% |");
  }

  @Test
  void buildOutputTruncatedTo200Lines() {
    var out = IntStream.rangeClosed(1, 500).mapToObj(i -> "line " + i).collect(joining("\n"));
    var md = FeedbackRenderer.render(1, new FeedbackInput.BuildFailed(out));
    assertThat(md)
        .startsWith("# Feedback on attempt 1: BUILD_FAILED")
        .contains("line 500")
        .contains("line 301")
        .doesNotContain("line 300\n");
  }

  @Test
  void contractViolationListsMismatches() {
    var md =
        FeedbackRenderer.render(
            1,
            new FeedbackInput.ContractViolation(
                List.of("missing: public int com.example.Foo.size()")));
    assertThat(md)
        .startsWith("# Feedback on attempt 1: CONTRACT_VIOLATION")
        .contains("- missing: public int com.example.Foo.size()");
  }

  @Test
  void noOutput() {
    assertThat(FeedbackRenderer.render(3, new FeedbackInput.NoOutput()))
        .startsWith("# Feedback on attempt 3: NO_OUTPUT")
        .contains("The previous attempt produced no commits.");
  }

  @Test
  void inputsReportTheirCategory() {
    assertThat(new FeedbackInput.NoOutput().category()).isEqualTo(Category.NO_OUTPUT);
    assertThat(new FeedbackInput.BehaviorFailure(List.of()).category())
        .isEqualTo(Category.BEHAVIOR_FAILURE);
  }
}
