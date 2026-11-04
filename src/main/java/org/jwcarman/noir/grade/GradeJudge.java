package org.jwcarman.noir.grade;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.feedback.GradeAggregator;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.feedback.TestOutcome.Result;
import org.jwcarman.noir.holdout.TagIndex;
import org.jwcarman.noir.reports.JUnitXmlReports;

/** Turns grade artifacts into a verdict (spec §8). */
public final class GradeJudge {
  static final int TAIL = 60;

  private GradeJudge() {}

  public static GradeOutcome judge(GradeArtifacts a, TagIndex tags, List<String> requirementIds) {
    var compile = a.exit("holdout-compile");
    if (compile.isEmpty()) {
      return new GradeOutcome.HoldoutDefect(
          "grader did not complete step holdout-compile:\n" + a.logTail("holdout-compile", TAIL));
    }
    if (compile.get() != 0) {
      return new GradeOutcome.HoldoutDefect(a.logTail("holdout-compile", TAIL));
    }
    Map<String, List<Result>> resultsByTest = new LinkedHashMap<>();
    for (int n = 1;
        Files.exists(a.path("run-" + n + ".exit")) || Files.exists(a.path("run-" + n));
        n++) {
      for (var o : JUnitXmlReports.parse(a.path("run-" + n))) {
        resultsByTest.computeIfAbsent(o.testId(), k -> new ArrayList<>()).add(o.result());
      }
    }
    var outcomes = new ArrayList<TestOutcome>();
    resultsByTest.forEach((id, results) ->
        outcomes.add(
            new TestOutcome(id, java.util.Set.of(), combine(results), "")
                .withTags(tags.tagsFor(id))));
    var aggregated = GradeAggregator.aggregate(outcomes, requirementIds);
    boolean allPassed =
        !outcomes.isEmpty() && outcomes.stream().allMatch(o -> o.result() == Result.PASSED);
    return allPassed ? new GradeOutcome.Passed(aggregated) : new GradeOutcome.Failed(aggregated);
  }

  /** Passed everywhere is PASSED; failing everywhere is FAILED/TIMED_OUT; anything else is not. */
  private static Result combine(List<Result> results) {
    if (results.contains(Result.SKIPPED)) {
      return Result.FAILED;
    }
    if (results.stream().allMatch(r -> r == Result.PASSED)) {
      return Result.PASSED;
    }
    if (results.stream().noneMatch(r -> r == Result.PASSED)) {
      return results.stream().allMatch(r -> r == Result.TIMED_OUT)
          ? Result.TIMED_OUT
          : Result.FAILED;
    }
    return Result.FLAKY;
  }
}
