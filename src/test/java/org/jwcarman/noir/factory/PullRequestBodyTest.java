package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.deliver.FileChange;
import org.jwcarman.noir.feedback.RequirementResult;

class PullRequestBodyTest {
  static final PullRequestBody.Provenance PROV =
      new PullRequestBody.Provenance(
          "0123abc",
          2,
          "f00d",
          "claude-opus",
          "claude-examiner",
          "sha256:b1",
          "sha256:9f86d081884c",
          new BigDecimal("0.198197"),
          new BigDecimal("0.3836754"));
  static final List<RequirementResult> RESULTS =
      List.of(new RequirementResult("R1", 4, 4), new RequirementResult("R2", 3, 3));

  /** A change with nothing PIT can mutate (a message, a constant) is not a 100% score. */
  @Test
  void mutationScoreWithoutMutantsIsNotApplicable() {
    var body =
        PullRequestBody.render(
            1,
            Optional.of(new PullRequestBody.Metrics(1.0, null, 2.0)),
            RESULTS,
            PROV,
            List.of(),
            List.of(),
            false);
    assertThat(body).contains("| Mutation score | n/a (nothing to mutate) |");
  }

  @Test
  void rendersExactSections() {
    var body =
        PullRequestBody.render(
            1,
            Optional.of(new PullRequestBody.Metrics(0.85, 0.725, 6.0)),
            RESULTS,
            PROV,
            List.of(),
            List.of(),
            false);
    assertThat(body)
        .isEqualTo(
            """
            Closes #1.

            | Metric | Value |
            |---|---|
            | Line coverage | 85.0% |
            | Mutation score | 72.5% |
            | Max CRAP (changed classes) | 6.0 |

            ### Holdout results

            R1: 4/4 passed
            R2: 3/3 passed

            ### Provenance

            - Base SHA: `0123abc`
            - Attempts: 2
            - Commitment: `f00d` (posted on #1)
            - Builder model: claude-opus
            - Examiner model: claude-examiner
            - Builder image: `sha256:b1`
            - Grader image: `sha256:9f86d081884c`
            - Cost: $0.58 (builder $0.20, Examiner $0.38; as reported by Claude Code)
            """);
  }

  @Test
  void costWithOnlyOnePartReportedCountsTheOtherAsUnknown() {
    var prov =
        new PullRequestBody.Provenance(
            "abc", 1, "f00d", "m", "e", "b", "g", new BigDecimal("1.255"), null);
    assertThat(
            PullRequestBody.render(1, Optional.empty(), RESULTS, prov, List.of(), List.of(), false))
        .contains("- Cost: $1.26 (builder $1.26, Examiner unknown; as reported by Claude Code)\n");
  }

  @Test
  void draftLeadsWithTheShortfallAndToleratesMissingData() {
    var body =
        PullRequestBody.render(
            9,
            Optional.empty(),
            List.of(),
            new PullRequestBody.Provenance("abc", 4, "f00d", null, null, null, "g", null, null),
            List.of(),
            List.of(),
            true);
    assertThat(body)
        .startsWith(
            "**noir could not satisfy every requirement within 4 attempts.**\n\nCloses #9.\n")
        .contains("| Line coverage | n/a |")
        .contains("### Holdout results\n\nNot graded.\n")
        .contains("- Builder model: unknown\n")
        .contains("- Examiner model: unknown\n")
        .contains("- Builder image: `unknown`\n")
        .contains("- Cost: unknown\n");
  }

  @Test
  void builderReportedValuesCannotBreakOutOfTheirLine() {
    var body =
        PullRequestBody.render(
            1,
            Optional.empty(),
            RESULTS,
            new PullRequestBody.Provenance(
                "abc", 1, "f00d", "m`\n### Injected", "e", "id`x\ny", "g", null, null),
            List.of(),
            List.of(),
            false);
    assertThat(body)
        .contains("- Builder model: m ### Injected\n")
        .contains("- Builder image: `idx y`\n")
        .doesNotContain("\n### Injected");
  }

  @Test
  void failureCommentListsPerRequirementResults() {
    assertThat(PullRequestBody.failureComment(4, RESULTS))
        .isEqualTo(
            """
            **noir could not satisfy every requirement within 4 attempts.** No attempt produced \
            commits that could be delivered, so no pull request was opened.

            ### Holdout results

            R1: 4/4 passed
            R2: 3/3 passed
            """);
  }

  @Test
  void rendersFilesSection() {
    var files =
        List.of(
            new FileChange(FileChange.Status.MODIFIED, "src/Calc.java", null, 12, 3),
            new FileChange(FileChange.Status.MODIFIED, "src/CalcTest.java", null, 2, 2),
            new FileChange(FileChange.Status.ADDED, "src/Helper.java", null, 40, 0),
            new FileChange(FileChange.Status.DELETED, "src/Gone.java", null, 0, 9),
            new FileChange(FileChange.Status.RENAMED, "src/New.java", "src/Old.java", 1, 1));
    var body =
        PullRequestBody.render(
            1, Optional.empty(), RESULTS, PROV, files, List.of("src/CalcTest.java"), false);
    assertThat(body)
        .contains(
            """
            ### Files

            - Added: `src/Helper.java` (+40)
            - Modified: `src/Calc.java` (+12 \u22123)
            - Deleted: `src/Gone.java`
            - Renamed: `src/Old.java` \u2192 `src/New.java` (+1 \u22121)
            - Tests updated: `src/CalcTest.java` (+2 \u22122)

            ### Provenance
            """);
    assertThat(body.indexOf("### Files")).isGreaterThan(body.indexOf("### Holdout results"));
  }

  @Test
  void deletedOnlyStillRendersFilesSection() {
    var files = List.of(new FileChange(FileChange.Status.DELETED, "src/Gone.java", null, 0, 9));
    var body = PullRequestBody.render(1, Optional.empty(), RESULTS, PROV, files, List.of(), true);
    assertThat(body).contains("### Files\n\n- Deleted: `src/Gone.java`\n\n### Provenance");
  }

  @Test
  void noFilesSectionWhenEmpty() {
    var body =
        PullRequestBody.render(1, Optional.empty(), RESULTS, PROV, List.of(), List.of(), false);
    assertThat(body).doesNotContain("### Files");
  }
}
