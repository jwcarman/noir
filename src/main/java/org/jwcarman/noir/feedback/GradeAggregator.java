package org.jwcarman.noir.feedback;

import java.util.ArrayList;
import java.util.List;

/** Reduces per-test outcomes to per-requirement counts (spec §8.2). */
public final class GradeAggregator {
  private GradeAggregator() {}

  public static List<RequirementResult> aggregate(
      List<TestOutcome> outcomes, List<String> requirementIds) {
    var results = new ArrayList<RequirementResult>();
    for (var id : requirementIds) {
      int passed = 0;
      int total = 0;
      for (var o : outcomes) {
        if (o.tags().contains(id)) {
          total++;
          if (o.result() == TestOutcome.Result.PASSED) {
            passed++;
          }
        }
      }
      results.add(new RequirementResult(id, passed, total));
    }
    return results;
  }
}
