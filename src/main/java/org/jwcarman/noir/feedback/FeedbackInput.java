package org.jwcarman.noir.feedback;

import java.util.List;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.measure.MethodCrap;
import org.jwcarman.noir.spec.QualityBar;

/** The coarse information allowed into FEEDBACK.md, per failure category. */
public sealed interface FeedbackInput {
  Category category();

  /** {@code lockedTestsFailed}: existing tests the spec does not let change that failed. */
  record BuildFailed(String mavenOutput, List<String> lockedTestsFailed) implements FeedbackInput {
    public BuildFailed {
      lockedTestsFailed = List.copyOf(lockedTestsFailed);
    }

    public BuildFailed(String mavenOutput) {
      this(mavenOutput, List.of());
    }

    public Category category() {
      return Category.BUILD_FAILED;
    }
  }

  record ContractViolation(List<String> mismatches) implements FeedbackInput {
    public ContractViolation {
      mismatches = List.copyOf(mismatches);
    }

    public Category category() {
      return Category.CONTRACT_VIOLATION;
    }
  }

  record QualityShortfall(
      QualityBar bar, List<ClassQuality> classes, List<MethodCrap> crapViolations)
      implements FeedbackInput {
    public QualityShortfall {
      classes = List.copyOf(classes);
      crapViolations = List.copyOf(crapViolations);
    }

    public Category category() {
      return Category.QUALITY_SHORTFALL;
    }
  }

  record BehaviorFailure(List<RequirementResult> results) implements FeedbackInput {
    public BehaviorFailure {
      results = List.copyOf(results);
    }

    public Category category() {
      return Category.BEHAVIOR_FAILURE;
    }
  }

  record NoOutput() implements FeedbackInput {
    public Category category() {
      return Category.NO_OUTPUT;
    }
  }
}
