package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.spec.SpecProblems;
import org.jwcarman.noir.spec.SpecProblems.Problem;

class SpecProblemCommentTest {
  static final SpecProblems REPORT =
      new SpecProblems(
          List.of(
              new Problem("R1", List.of("a/ATest.java", "b/B.java"), "R1 conflicts."),
              new Problem("R2", List.of(), "R2 contradicts its example."),
              new Problem(null, List.of("c/C.java"), "C cannot change.")));

  @Test
  void builderTextIsExact() {
    assertThat(SpecProblemComment.render("builder", REPORT))
        .isEqualTo(
            "noir stopped work on this issue: the builder reports that the spec cannot be"
                + " satisfied as written. noir has not verified this; please check it.\n\n"
                + "- **R1** (`a/ATest.java`, `b/B.java`): R1 conflicts.\n"
                + "- **R2**: R2 contradicts its example.\n"
                + "- (`c/C.java`): C cannot change.\n\n"
                + "Revise the spec (for example, list a conflicting test under"
                + " `## Tests to update`), then add `noir:ready` again.");
  }

  @Test
  void examinerTextNamesTheExaminer() {
    assertThat(SpecProblemComment.render("Examiner", REPORT))
        .startsWith("noir stopped work on this issue: the Examiner reports");
  }
}
