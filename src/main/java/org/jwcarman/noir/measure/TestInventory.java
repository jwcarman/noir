package org.jwcarman.noir.measure;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.json.Json;

/**
 * The repository's existing tests as a lock: every test that passed on the base commit must still
 * pass on the builder's branch, unless the spec lets its file change. Path rules cannot see every
 * way of silencing a test (main code can throw JUnit's abort exception by reflection, which reports
 * the test as skipped), so the check compares outcomes instead. Test ids are {@code classname#name}
 * as the JUnit XML reports give them.
 */
public final class TestInventory {
  /** The base commit's passing test ids, in the item directory (written at sealing). */
  public static final String BASELINE_FILE = "baseline-tests.json";

  /** The most test ids named in builder feedback. */
  static final int FEEDBACK_LIMIT = 20;

  private static final String TEST_JAVA = "src/test/java/";

  private TestInventory() {}

  /** The ids of the tests that passed, sorted and unique. */
  public static List<String> passed(List<TestOutcome> outcomes) {
    return ids(outcomes, r -> r == TestOutcome.Result.PASSED);
  }

  /** The ids of the tests that failed or timed out, sorted and unique. */
  public static List<String> failing(List<TestOutcome> outcomes) {
    return ids(outcomes, r -> r == TestOutcome.Result.FAILED || r == TestOutcome.Result.TIMED_OUT);
  }

  /**
   * The baseline tests that did not pass on the branch (skipped, failed or not run at all), in
   * baseline order. Tests whose source file is in {@code changeablePaths} are exempt.
   */
  public static List<String> notPassing(
      List<String> baselinePassed, List<TestOutcome> branch, Collection<String> changeablePaths) {
    var passedNow = new HashSet<>(passed(branch));
    var exempt = Set.copyOf(changeablePaths);
    var result = new ArrayList<String>();
    for (var id : baselinePassed) {
      if (!passedNow.contains(id) && !exempt.contains(sourcePath(id))) {
        result.add(id);
      }
    }
    return result;
  }

  /**
   * The baseline tests that failed or timed out in {@code outcomes}, in baseline order, except
   * those in files the spec lets change. Tests that were skipped or did not run are not listed.
   */
  public static List<String> failedLocked(
      List<String> baselinePassed, List<TestOutcome> outcomes, Collection<String> changeablePaths) {
    var failed = new HashSet<>(failing(outcomes));
    var exempt = Set.copyOf(changeablePaths);
    return baselinePassed.stream()
        .filter(id -> failed.contains(id) && !exempt.contains(sourcePath(id)))
        .toList();
  }

  /** Builder feedback naming the tests (at most {@value #FEEDBACK_LIMIT}). */
  public static String feedback(List<String> notPassing) {
    var shown =
        notPassing.stream()
            .limit(FEEDBACK_LIMIT)
            .map(id -> "`" + id.replaceAll("\\p{Cntrl}+", " ").replace("`", "") + "`")
            .collect(Collectors.joining(", "));
    var more = notPassing.size() - FEEDBACK_LIMIT;
    return "These existing tests passed on the base commit but did not pass on your branch"
        + " (skipped or not run): "
        + shown
        + (more > 0 ? ", and " + more + " more." : ".")
        + " Existing tests are locked: they must run and pass. Find what stops them running and"
        + " remove it.";
  }

  public static void write(List<String> ids, Path file) {
    try {
      Json.mapper().writeValue(file.toFile(), ids);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static List<String> read(Path file) {
    try {
      return List.copyOf(
          Json.mapper().readValue(file.toFile(), new TypeReference<List<String>>() {}));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code a.b.C$Inner#m} → {@code src/test/java/a/b/C.java}. */
  public static String sourcePath(String id) {
    var hash = id.indexOf('#');
    var cls = hash < 0 ? id : id.substring(0, hash);
    var dollar = cls.indexOf('$');
    var outer = dollar < 0 ? cls : cls.substring(0, dollar);
    return TEST_JAVA + outer.replace('.', '/') + ".java";
  }

  private static List<String> ids(
      List<TestOutcome> outcomes, java.util.function.Predicate<TestOutcome.Result> keep) {
    return outcomes.stream()
        .filter(o -> keep.test(o.result()))
        .map(TestOutcome::testId)
        .collect(Collectors.toCollection(TreeSet::new))
        .stream()
        .toList();
  }
}
