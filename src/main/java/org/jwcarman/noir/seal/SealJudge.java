package org.jwcarman.noir.seal;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.reports.JUnitXmlReports;
import org.jwcarman.noir.reports.PitMutations;

/** Turns seal-check artifacts into problems (spec §5.3). An empty list means sealable. */
public final class SealJudge {
  static final int TAIL = 60;

  /** Prefix of the check-4 problem: {@code <prefix><ClassName>#<method>}. */
  public static final String EXISTING_TEST_FAILS = "existing test fails with the reference: ";

  static final String EXISTING_TESTS_DO_NOT_COMPILE =
      "existing tests do not compile with the reference";

  private SealJudge() {}

  public static List<String> judge(SealArtifacts a, double discriminationThreshold) {
    var problems = new ArrayList<String>();
    if (!stepOk(a, "clone", problems) || !stepCompleted(a, "assumptions", problems)) {
      return problems;
    }
    checkAssumptions(a, problems);
    if (!problems.isEmpty()) {
      return problems;
    }
    if (!compiled(a, "stub-build", "API stubs do not compile", problems)
        || !compiled(
            a, "holdout-compile", "holdout tests do not compile against the stubs", problems)
        || !stepCompleted(a, "stub-run", problems)) {
      return problems;
    }
    var stubOutcomes = JUnitXmlReports.parse(a.path("stub-run"));
    if (stubOutcomes.isEmpty()) {
      return List.of("no holdout tests were discovered");
    }
    for (var o : stubOutcomes) {
      if (o.result() == TestOutcome.Result.SKIPPED) {
        problems.add(skipped(o.testId()));
      }
    }
    if (!problems.isEmpty()) {
      return problems;
    }
    for (var o : stubOutcomes) {
      if (o.result() == TestOutcome.Result.PASSED) {
        problems.add("holdout test passes against the stubs (vacuous): " + o.testId());
      }
    }
    if (!problems.isEmpty()) {
      return problems;
    }
    if (!compiled(a, "ref-build", "reference implementation does not compile", problems)) {
      return problems;
    }
    checkReferenceRuns(a, stubOutcomes, problems);
    if (!problems.isEmpty()) {
      return problems;
    }
    checkDiscrimination(a, discriminationThreshold, problems);
    checkExistingTests(a, problems);
    return problems;
  }

  /**
   * Check 4: the repository's own tests against the reference. Only modify specs run them, so a
   * missing step (neither log nor exit) means there is nothing to check.
   */
  private static void checkExistingTests(SealArtifacts a, List<String> problems) {
    var step = "existing-tests";
    if (a.exit(step).isEmpty() && !Files.exists(a.path(step + ".log"))) {
      return;
    }
    if (!stepCompleted(a, step, problems)) {
      return;
    }
    var outcomes = JUnitXmlReports.parse(a.path(step));
    var failed = false;
    for (var o : outcomes) {
      if (o.result() != TestOutcome.Result.PASSED && o.result() != TestOutcome.Result.SKIPPED) {
        problems.add(EXISTING_TEST_FAILS + o.testId());
        failed = true;
      }
    }
    if (!failed && a.exit(step).orElseThrow() != 0) {
      problems.add(EXISTING_TESTS_DO_NOT_COMPILE + ":\n" + a.logTail(step, TAIL));
    }
  }

  private static void checkAssumptions(SealArtifacts a, List<String> problems) {
    if (!a.read("pom.txt").equals("yes")) {
      problems.add("pom.xml not found at repository root");
      return;
    }
    if (a.read("modules.txt").equals("yes")) {
      problems.add("multi-module Maven builds are not supported");
    }
    var release = a.read("release.txt");
    if (!release.equals("21")) {
      var found = release.matches("\\d+") ? release : "unset";
      problems.add("maven.compiler.release must be 21 (found " + found + ")");
    }
  }

  private static void checkReferenceRuns(
      SealArtifacts a, List<TestOutcome> expectedTests, List<String> problems) {
    Map<String, Set<Boolean>> resultsByTest = new LinkedHashMap<>();
    var skippedTests = new LinkedHashSet<String>();
    expectedTests.forEach(o -> resultsByTest.put(o.testId(), new LinkedHashSet<>()));
    int runs = 0;
    for (int n = 1;
        Files.exists(a.path("ref-run-" + n + ".exit")) || Files.exists(a.path("ref-run-" + n));
        n++) {
      if (!stepCompleted(a, "ref-run-" + n, problems)) {
        return;
      }
      runs++;
      var passed = new LinkedHashSet<String>();
      for (var o : JUnitXmlReports.parse(a.path("ref-run-" + n))) {
        if (o.result() == TestOutcome.Result.PASSED) {
          passed.add(o.testId());
        } else if (o.result() == TestOutcome.Result.SKIPPED) {
          skippedTests.add(o.testId());
        }
      }
      resultsByTest.forEach((id, seen) -> seen.add(passed.contains(id)));
    }
    if (runs == 0) {
      problems.add("seal check did not complete step ref-run-1:\n" + a.logTail("ref-run-1", TAIL));
      return;
    }
    skippedTests.forEach(id -> problems.add(skipped(id)));
    resultsByTest.forEach((id, seen) -> {
      if (skippedTests.contains(id)) {
        return;
      }
      if (seen.equals(Set.of(false))) {
        problems.add("holdout test fails against the reference implementation: " + id);
      } else if (seen.size() > 1) {
        problems.add("holdout test is nondeterministic: " + id);
      }
    });
  }

  private static void checkDiscrimination(
      SealArtifacts a, double threshold, List<String> problems) {
    var xml = a.path("pit/mutations.xml");
    if (!Files.isRegularFile(xml)) {
      var tail = a.logTail("pit", TAIL);
      problems.add("mutation testing produced no mutants" + (tail.isBlank() ? "" : ":\n" + tail));
      return;
    }
    var summary = PitMutations.parse(xml);
    if (summary.total() == 0) {
      problems.add("mutation testing produced no mutants");
    } else if (summary.score() < threshold) {
      problems.add(
          String.format(
              Locale.ROOT,
              "holdout tests are not discriminating enough: mutation score %.2f < %.2f"
                  + " (%d of %d mutants killed)",
              summary.score(),
              threshold,
              summary.detected(),
              summary.total()));
    }
  }

  private static String skipped(String testId) {
    return "holdout test is skipped (disabled or a failed assumption): " + testId;
  }

  private static boolean compiled(
      SealArtifacts a, String step, String message, List<String> problems) {
    if (!stepCompleted(a, step, problems)) {
      return false;
    }
    if (a.exit(step).orElseThrow() != 0) {
      problems.add(message + ":\n" + a.logTail(step, TAIL));
      return false;
    }
    return true;
  }

  private static boolean stepOk(SealArtifacts a, String step, List<String> problems) {
    if (!stepCompleted(a, step, problems)) {
      return false;
    }
    if (a.exit(step).orElseThrow() != 0) {
      problems.add("seal check failed at step " + step + ":\n" + a.logTail(step, TAIL));
      return false;
    }
    return true;
  }

  private static boolean stepCompleted(SealArtifacts a, String step, List<String> problems) {
    if (a.exit(step).isEmpty()) {
      problems.add("seal check did not complete step " + step + ":\n" + a.logTail(step, TAIL));
      return false;
    }
    return true;
  }
}
