package org.jwcarman.noir.feedback;

import java.util.List;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.spec.QualityBar;

/** The coarse information allowed into FEEDBACK.md, per failure category. */
public sealed interface FeedbackInput {
    Category category();

    record BuildFailed(String mavenOutput) implements FeedbackInput {
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

    record QualityShortfall(QualityBar bar, List<ClassQuality> classes) implements FeedbackInput {
        public QualityShortfall {
            classes = List.copyOf(classes);
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
