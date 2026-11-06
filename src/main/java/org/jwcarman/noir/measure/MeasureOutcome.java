package org.jwcarman.noir.measure;

import java.util.List;
import org.jwcarman.noir.feedback.FeedbackInput;

/** Result of judging a measurement run. */
public sealed interface MeasureOutcome {
  Metrics m();

  /**
   * @param mutants how many mutants the score counts; 0 means nothing measured could be mutated
   *     (the score is then 1.0 by convention)
   */
  record Metrics(
      double lineCoverage,
      double mutationScore,
      int mutants,
      double maxCrap,
      List<String> changedClasses) {
    public Metrics {
      changedClasses = List.copyOf(changedClasses);
    }

    static Metrics empty() {
      return new Metrics(0, 0, 0, 0, List.of());
    }
  }

  record Passed(Metrics m) implements MeasureOutcome {}

  record Shortfall(FeedbackInput input, Metrics m) implements MeasureOutcome {}
}
