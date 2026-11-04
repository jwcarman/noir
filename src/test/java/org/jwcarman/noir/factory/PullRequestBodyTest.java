package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
          "sha256:9f86d081884c");
  static final List<RequirementResult> RESULTS =
      List.of(new RequirementResult("R1", 4, 4), new RequirementResult("R2", 3, 3));

  @Test
  void rendersExactSections() {
    var body =
        PullRequestBody.render(
            1, Optional.of(new PullRequestBody.Metrics(0.85, 0.725, 6.0)), RESULTS, PROV, false);
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
            """);
  }

  @Test
  void draftLeadsWithTheShortfallAndToleratesMissingData() {
    var body =
        PullRequestBody.render(
            9,
            Optional.empty(),
            List.of(),
            new PullRequestBody.Provenance("abc", 4, "f00d", null, null, null, "g"),
            true);
    assertThat(body)
        .startsWith(
            "**noir could not satisfy every requirement within 4 attempts.**\n\nCloses #9.\n")
        .contains("| Line coverage | n/a |")
        .contains("### Holdout results\n\nNot graded.\n")
        .contains("- Builder model: unknown\n")
        .contains("- Examiner model: unknown\n")
        .contains("- Builder image: `unknown`\n");
  }

  @Test
  void builderReportedValuesCannotBreakOutOfTheirLine() {
    var body =
        PullRequestBody.render(
            1,
            Optional.empty(),
            RESULTS,
            new PullRequestBody.Provenance(
                "abc", 1, "f00d", "m`\n### Injected", "e", "id`x\ny", "g"),
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
}
