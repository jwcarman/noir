package org.jwcarman.noir.grade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.feedback.GradeAggregator;
import org.jwcarman.noir.feedback.RequirementResult;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.feedback.TestOutcome.Result;
import org.jwcarman.noir.holdout.TagIndex;

/** Turns grade artifacts into a verdict (spec §8). */
public final class GradeJudge {
  static final int TAIL = 60;

  private GradeJudge() {}

  public static GradeOutcome judge(GradeArtifacts a, TagIndex tags, List<String> requirementIds) {
    if (!a.read("forbidden.txt").isEmpty()) {
      return new GradeOutcome.Failed(
          requirementIds.stream().map(id -> new RequirementResult(id, 0, 0)).toList());
    }
    int compile = a.exit("holdout-compile").orElseThrow(() -> missing("holdout-compile"));
    if (compile != 0) {
      return new GradeOutcome.HoldoutDefect(a.logTail("holdout-compile", TAIL));
    }
    int run1 = a.exit("run-1").orElseThrow(() -> missing("run-1"));
    int runs = 1;
    if (run1 != 0) {
      a.exit("run-2").orElseThrow(() -> missing("run-2"));
      a.exit("run-3").orElseThrow(() -> missing("run-3"));
      runs = 3;
    }
    // Per run: test id -> result (a duplicated id passes only if every occurrence passed).
    var perRun = new ArrayList<Map<String, Result>>();
    var ids = new LinkedHashSet<String>();
    for (int n = 1; n <= runs; n++) {
      var byId = new LinkedHashMap<String, Result>();
      for (var o : a.outcomes("run-" + n)) {
        byId.merge(
            o.testId(), o.result(), (x, y) -> x == Result.PASSED && y == x ? x : Result.FAILED);
      }
      ids.addAll(byId.keySet());
      perRun.add(byId);
    }
    var outcomes = new ArrayList<TestOutcome>();
    var covered = new LinkedHashSet<String>();
    for (var id : ids) {
      covered.add(key(id));
      var results = new ArrayList<Result>();
      perRun.forEach(r -> results.add(r.getOrDefault(id, Result.FAILED)));
      outcomes.add(new TestOutcome(id, tags.tagsFor(id), combine(results), ""));
    }
    // Every sealed test must show up; one that never reported (filtered out) counts as failed.
    tags.tagsByTest()
        .forEach((key, testTags) -> {
          if (!covered.contains(key)) {
            outcomes.add(new TestOutcome(key, testTags, Result.FAILED, ""));
          }
        });
    var aggregated = GradeAggregator.aggregate(outcomes, requirementIds);
    boolean allPassed =
        run1 == 0
            && !outcomes.isEmpty()
            && outcomes.stream().allMatch(o -> o.result() == Result.PASSED);
    return allPassed ? new GradeOutcome.Passed(aggregated) : new GradeOutcome.Failed(aggregated);
  }

  private static IllegalStateException missing(String step) {
    return new IllegalStateException("grade step " + step + " has no recorded exit");
  }

  /** {@code pkg.T$Inner#m(int)[1]} to {@code pkg.T#m}, the form {@link TagIndex} is keyed by. */
  static String key(String junitTestId) {
    int hash = junitTestId.indexOf('#');
    if (hash < 0) {
      return junitTestId;
    }
    var name = junitTestId.substring(hash + 1);
    int paren = name.indexOf('(');
    var method = paren < 0 ? name : name.substring(0, paren);
    var cls = junitTestId.substring(0, hash);
    int dollar = cls.indexOf('$');
    if (dollar >= 0) {
      cls = cls.substring(0, dollar);
    }
    return cls + "#" + method;
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
