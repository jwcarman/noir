package org.jwcarman.noir.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.noir.feedback.TestOutcome.Result.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GradeAggregatorTest {
  @Test
  void countsPerTagAndOrdersByRequirement() {
    var outcomes =
        List.of(
            new TestOutcome("T#a", Set.of("R1"), PASSED, null),
            new TestOutcome("T#b", Set.of("R1", "R2"), FAILED, "expected 4 but was 5"),
            new TestOutcome("T#c", Set.of("R2"), FLAKY, null),
            new TestOutcome("T#d", Set.of("R2"), TIMED_OUT, null));
    assertThat(GradeAggregator.aggregate(outcomes, List.of("R1", "R2", "R3")))
        .containsExactly(
            new RequirementResult("R1", 1, 2),
            new RequirementResult("R2", 0, 3),
            new RequirementResult("R3", 0, 0));
  }

  @Test
  void tagsOutsideRequirementListIgnored() {
    var outcomes = List.of(new TestOutcome("T#a", Set.of("R1", "slow"), PASSED, null));
    assertThat(GradeAggregator.aggregate(outcomes, List.of("R1")))
        .containsExactly(new RequirementResult("R1", 1, 1));
  }
}
