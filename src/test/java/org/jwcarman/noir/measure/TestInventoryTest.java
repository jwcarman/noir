package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.feedback.TestOutcome.Result;

class TestInventoryTest {

  private static TestOutcome t(String id, Result r) {
    return new TestOutcome(id, Set.of(), r, null);
  }

  @Test
  void passedKeepsOnlyPassingTestsSortedAndUnique() {
    assertThat(
            TestInventory.passed(
                List.of(
                    t("b.T#y", Result.PASSED),
                    t("a.T#x", Result.PASSED),
                    t("a.T#x", Result.PASSED),
                    t("a.T#s", Result.SKIPPED),
                    t("a.T#f", Result.FAILED))))
        .containsExactly("a.T#x", "b.T#y");
  }

  @Test
  void failingReportsFailedAndTimedOutTests() {
    assertThat(
            TestInventory.failing(
                List.of(
                    t("a.T#x", Result.PASSED),
                    t("a.T#f", Result.FAILED),
                    t("a.T#t", Result.TIMED_OUT),
                    t("a.T#s", Result.SKIPPED))))
        .containsExactly("a.T#f", "a.T#t");
  }

  @Test
  void aBaselineTestSkippedOnTheBranchIsNotRun() {
    assertThat(
            TestInventory.notPassing(
                List.of("a.T#x", "a.T#y"),
                List.of(t("a.T#x", Result.PASSED), t("a.T#y", Result.SKIPPED)),
                List.of()))
        .containsExactly("a.T#y");
  }

  @Test
  void aBaselineTestMissingFromTheBranchIsNotRun() {
    assertThat(
            TestInventory.notPassing(
                List.of("a.T#x", "b.U#z"), List.of(t("a.T#x", Result.PASSED)), List.of()))
        .containsExactly("b.U#z");
  }

  @Test
  void aBaselineTestPassingOnTheBranchIsFine() {
    assertThat(
            TestInventory.notPassing(
                List.of("a.T#x"),
                List.of(t("a.T#x", Result.PASSED), t("a.T#new", Result.PASSED)),
                List.of()))
        .isEmpty();
  }

  @Test
  void testsInFilesTheSpecMayChangeAreExempt() {
    // Outer class, nested class and the unnamed package all map to their source file.
    assertThat(
            TestInventory.notPassing(
                List.of("a.b.CsvTest#x", "a.b.CsvTest$Nested#y", "Top#z", "a.b.Other#w"),
                List.of(),
                List.of("src/test/java/a/b/CsvTest.java", "src/test/java/Top.java")))
        .containsExactly("a.b.Other#w");
  }

  @Test
  void baselineRoundTripsThroughAFile(@TempDir Path dir) {
    var file = dir.resolve("baseline-tests.json");
    var ids = List.of("a.T#x", "a.T#weird name\nwith newline", "b.U#[1] \"q\"");
    TestInventory.write(ids, file);
    assertThat(TestInventory.read(file)).containsExactlyElementsOf(ids);
  }

  @Test
  void feedbackNamesTheTestsAndCapsTheList() {
    var many = java.util.stream.IntStream.range(0, 25).mapToObj(i -> "a.T#t" + (100 + i)).toList();
    var text = TestInventory.feedback(many);
    assertThat(text)
        .startsWith(
            "These existing tests passed on the base commit but did not pass on your branch"
                + " (skipped or not run): `a.T#t100`, ")
        .contains("`a.T#t119`, and 5 more.")
        .doesNotContain("a.T#t120")
        .endsWith(
            " Existing tests are locked: they must run and pass. Find what stops them running and"
                + " remove it.");
  }
}
