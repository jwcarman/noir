package org.jwcarman.noir.seal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.measure.PitByClass;
import org.jwcarman.noir.reports.JUnitXmlReports;
import org.jwcarman.noir.reports.PitMutations;
import org.jwcarman.noir.reports.PitSummary;

/** Turns seal-check artifacts into problems (spec §5.3). An empty list means sealable. */
public final class SealJudge {
  static final int TAIL = 60;

  /** Prefix of the check-4 problem: {@code <prefix><ClassName>#<method>}. */
  public static final String EXISTING_TEST_FAILS = "existing test fails with the reference: ";

  /** Check 4 could not run: a build, dependency or fork failure with no failed test. */
  static final String EXISTING_TESTS_COULD_NOT_RUN =
      "existing tests could not run with the reference";

  private SealJudge() {}

  public static List<String> judge(SealArtifacts a, double discriminationThreshold) {
    return judge(a, discriminationThreshold, Map.of());
  }

  /**
   * Judges the artifacts. {@code changedLinesByClass} maps each top-level class of a modify target
   * to the reference lines it added or changed against the base file: check 3 counts only that
   * class's mutants on those lines. Every other class (a new file) is counted whole.
   */
  public static List<String> judge(
      SealArtifacts a,
      double discriminationThreshold,
      Map<String, Set<Integer>> changedLinesByClass) {
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
    checkDiscrimination(a, discriminationThreshold, changedLinesByClass, problems);
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
      problems.add(EXISTING_TESTS_COULD_NOT_RUN + ":\n" + a.logTail(step, TAIL));
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
      SealArtifacts a,
      double threshold,
      Map<String, Set<Integer>> changedLinesByClass,
      List<String> problems) {
    var xml = a.path("pit/mutations.xml");
    if (!Files.isRegularFile(xml)) {
      var tail = a.logTail("pit", TAIL);
      problems.add("mutation testing produced no mutants" + (tail.isBlank() ? "" : ":\n" + tail));
      return;
    }
    var whole = PitMutations.parse(xml);
    if (whole.total() == 0) {
      problems.add("mutation testing produced no mutants");
      return;
    }
    var summary =
        changedLinesByClass.isEmpty() ? whole : changedLineSummary(xml, whole, changedLinesByClass);
    // PIT mutated the code, but none of it on the changed lines (a constant, a static initializer
    // or Javadoc changed): there is nothing to score.
    if (summary.total() == 0) {
      return;
    }
    if (summary.score() < threshold) {
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

  /**
   * The whole report less the mutants of each class in {@code changedLinesByClass} that are not on
   * its changed lines (so a class with no changed lines contributes none). Every other mutant,
   * including one PIT names no class for, is counted.
   */
  private static PitSummary changedLineSummary(
      Path xml, PitSummary whole, Map<String, Set<Integer>> changedLinesByClass) {
    int total = whole.total();
    int detected = whole.detected();
    for (var entry : PitByClass.parseLines(xml).entrySet()) {
      var lines = changedLinesByClass.get(entry.getKey());
      if (lines == null) {
        continue;
      }
      for (var m : entry.getValue()) {
        if (!lines.contains(m.line())) {
          total--;
          if (m.detected()) {
            detected--;
          }
        }
      }
    }
    return new PitSummary(total, detected);
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
