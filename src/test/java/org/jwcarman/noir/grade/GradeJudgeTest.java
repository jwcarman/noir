package org.jwcarman.noir.grade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.feedback.RequirementResult;
import org.jwcarman.noir.holdout.TagIndex;

class GradeJudgeTest {
  static final String CLS = "noir.holdout.x.T";
  static final List<String> REQS = List.of("R1", "R2");
  static final TagIndex TAGS =
      new TagIndex(
          Map.of(
              CLS + "#a", Set.of("R1"),
              CLS + "#b", Set.of("R1"),
              CLS + "#c", Set.of("R2")));

  @TempDir Path out;

  @BeforeEach
  void compiled() {
    exit("holdout-compile", 0);
  }

  GradeOutcome judge() {
    return GradeJudge.judge(new GradeArtifacts(out), TAGS, REQS);
  }

  @Test
  void allPassInSingleRun() {
    exit("run-1", 0);
    junit("run-1", "a", true, "b", true, "c", true);
    assertThat(judge())
        .isEqualTo(
            new GradeOutcome.Passed(
                List.of(new RequirementResult("R1", 2, 2), new RequirementResult("R2", 1, 1))));
  }

  @Test
  void failsInAllThreeRuns() {
    for (int n = 1; n <= 3; n++) {
      exit("run-" + n, 1);
      junit("run-" + n, "a", false, "b", true, "c", true);
    }
    assertThat(judge())
        .isEqualTo(
            new GradeOutcome.Failed(
                List.of(new RequirementResult("R1", 1, 2), new RequirementResult("R2", 1, 1))));
  }

  @Test
  void failThenPassIsFlakyAndNotCounted() {
    exit("run-1", 1);
    junit("run-1", "a", false, "b", true, "c", true);
    exit("run-2", 0);
    junit("run-2", "a", true, "b", true, "c", true);
    exit("run-3", 0);
    junit("run-3", "a", true, "b", true, "c", true);
    assertThat(judge())
        .isEqualTo(
            new GradeOutcome.Failed(
                List.of(new RequirementResult("R1", 1, 2), new RequirementResult("R2", 1, 1))));
  }

  @Test
  void compileFailureIsHoldoutDefect() {
    exit("holdout-compile", 1);
    log("holdout-compile", "T.java:3: error");
    assertThat(judge()).isEqualTo(new GradeOutcome.HoldoutDefect("T.java:3: error"));
  }

  @Test
  void unfinishedCompileIsHoldoutDefect() throws IOException {
    Files.delete(out.resolve("holdout-compile.exit"));
    assertThat(judge()).isInstanceOf(GradeOutcome.HoldoutDefect.class);
  }

  @Test
  void skippedTestIsNotPassed() {
    exit("run-1", 0);
    file(
        "run-1/TEST-junit-jupiter.xml",
        "<testsuite>"
            + tc("a", true)
            + tc("b", true)
            + tc("c", true).replace("</testcase>", "<skipped/></testcase>")
            + "</testsuite>");
    assertThat(judge())
        .isEqualTo(
            new GradeOutcome.Failed(
                List.of(new RequirementResult("R1", 2, 2), new RequirementResult("R2", 0, 1))));
  }

  @Test
  void zeroTestsFailsWithZeroResults() {
    exit("run-1", 0);
    assertThat(judge())
        .isEqualTo(
            new GradeOutcome.Failed(
                List.of(new RequirementResult("R1", 0, 0), new RequirementResult("R2", 0, 0))));
  }

  void exit(String step, int code) {
    file(step + ".exit", String.valueOf(code));
  }

  void log(String step, String text) {
    file(step + ".log", text);
  }

  void junit(String dir, String n1, boolean p1, String n2, boolean p2, String n3, boolean p3) {
    file(
        dir + "/TEST-junit-jupiter.xml",
        "<testsuite>" + tc(n1, p1) + tc(n2, p2) + tc(n3, p3) + "</testsuite>");
  }

  static String tc(String name, boolean pass) {
    return "<testcase classname=\""
        + CLS
        + "\" name=\""
        + name
        + "()\">"
        + (pass ? "" : "<failure message=\"boom\"/>")
        + "</testcase>";
  }

  void file(String rel, String content) {
    try {
      var p = out.resolve(rel);
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
