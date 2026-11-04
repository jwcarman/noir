package org.jwcarman.noir.measure;

import java.util.List;
import org.jwcarman.noir.feedback.FeedbackInput;

/** Result of judging a measurement run. */
public sealed interface MeasureOutcome {
  Metrics m();

  record Metrics(
      double lineCoverage, double mutationScore, double maxCrap, List<String> changedClasses) {
    public Metrics {
      changedClasses = List.copyOf(changedClasses);
    }

    static Metrics empty() {
      return new Metrics(0, 0, 0, List.of());
    }
  }

  record Passed(Metrics m) implements MeasureOutcome {}

  record Shortfall(FeedbackInput input, Metrics m) implements MeasureOutcome {}
}
