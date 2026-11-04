package org.jwcarman.noir.grade;

import java.util.List;
import org.jwcarman.noir.feedback.RequirementResult;

/** The verdict on a builder's work from the sealed holdout tests. */
public sealed interface GradeOutcome {
  /** Every holdout test passed in every run it appeared in. */
  record Passed(List<RequirementResult> results) implements GradeOutcome {}

  /** At least one holdout test did not pass, or none ran. */
  record Failed(List<RequirementResult> results) implements GradeOutcome {}

  /** The holdout tests themselves do not compile; not the builder's fault. */
  record HoldoutDefect(String detail) implements GradeOutcome {}
}
