package org.jwcarman.noir.feedback;

import java.util.Arrays;
import java.util.Locale;

/**
 * Renders FEEDBACK.md for the builder. Never includes holdout test names, source, messages, or
 * traces.
 */
public final class FeedbackRenderer {
  static final int MAX_BUILD_LINES = 200;
  static final String BEHAVIOR_HINT =
      "Hidden acceptance tests check only the requirements in SPEC.md. Re-read the failing requirements and their examples.";

  private FeedbackRenderer() {}

  public static String render(int attempt, FeedbackInput input) {
    var sb = new StringBuilder();
    sb.append("# Feedback on attempt ")
        .append(attempt)
        .append(": ")
        .append(input.category())
        .append("\n\n");
    switch (input) {
      case FeedbackInput.BuildFailed b -> {
        sb.append("`mvn verify` failed. Last lines of output:\n\n```\n");
        sb.append(tail(b.mavenOutput(), MAX_BUILD_LINES)).append("\n```\n");
      }
      case FeedbackInput.ContractViolation c -> {
        sb.append(
            "The build breaks the contract in SPEC.md (public API vs. the stubs, reserved"
                + " packages, forbidden files). Fix each item:\n\n");
        c.mismatches().forEach(m -> sb.append("- ").append(m).append('\n'));
      }
      case FeedbackInput.QualityShortfall q -> {
        sb.append("Required: line coverage ≥ ")
            .append(pct(q.bar().lineCoverage()))
            .append(", mutation score ≥ ")
            .append(pct(q.bar().mutationScore()))
            .append("\n\n");
        sb.append("| Class | Line coverage | Mutation score |\n|---|---|---|\n");
        for (var c : q.classes()) {
          sb.append("| ")
              .append(c.className())
              .append(c.changedLinesOnly() ? " (changed lines only)" : "")
              .append(" | ")
              .append(pct(c.lineCoverage()))
              .append(" | ")
              .append(pct(c.mutationScore()))
              .append(" |\n");
        }
        if (q.classes().stream().anyMatch(ClassQuality::changedLinesOnly)) {
          sb.append(
              "\nFor files that already existed, only the lines this attempt added or changed are"
                  + " measured.\n");
        }
        if (!q.crapViolations().isEmpty()) {
          sb.append("\nMethods over the CRAP limit (max ")
              .append(q.bar().maxCrap())
              .append("):\n\n");
          sb.append("| Method | CRAP | Complexity | Line coverage |\n|---|---|---|---|\n");
          for (var m : q.crapViolations()) {
            sb.append("| ")
                .append(m.className())
                .append('.')
                .append(m.method())
                .append(" | ")
                .append(String.format(Locale.ROOT, "%.1f", m.crap()))
                .append(" | ")
                .append(m.complexity())
                .append(" | ")
                .append(pct(m.lineCoverage()))
                .append(" |\n");
          }
        }
      }
      case FeedbackInput.BehaviorFailure b -> {
        b.results()
            .forEach(r ->
                sb.append(r.requirementId())
                    .append(": ")
                    .append(r.passed())
                    .append('/')
                    .append(r.total())
                    .append(" passed\n"));
        sb.append('\n').append(BEHAVIOR_HINT).append('\n');
      }
      case FeedbackInput.NoOutput n -> sb.append("The previous attempt produced no commits.\n");
    }
    return sb.toString();
  }

  private static String pct(double v) {
    return String.format(Locale.ROOT, "%.1f%%", v * 100);
  }

  private static String tail(String text, int lines) {
    var all = text.split("\n", -1);
    return String.join("\n", Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
  }
}
