package org.jwcarman.noir.seal;

import java.util.stream.Collectors;
import org.jwcarman.noir.spec.SpecProblems;

/** The issue comment when an agent reports that the spec cannot be satisfied. */
public final class SpecProblemComment {
  private SpecProblemComment() {}

  /** {@code agent} is how the comment names the reporter ("builder" or "Examiner"). */
  public static String render(String agent, SpecProblems report) {
    var sb =
        new StringBuilder("noir stopped work on this issue: the ")
            .append(agent)
            .append(
                " reports that the spec cannot be satisfied as written. noir has not verified"
                    + " this; please check it.\n\n");
    for (var p : report.problems()) {
      sb.append("- ");
      if (p.requirement() != null) {
        sb.append("**").append(p.requirement()).append("**");
      }
      if (!p.files().isEmpty()) {
        if (p.requirement() != null) {
          sb.append(' ');
        }
        sb.append('(')
            .append(
                p.files().stream()
                    .map(f -> "`" + f.replace("`", "") + "`")
                    .collect(Collectors.joining(", ")))
            .append(')');
      }
      sb.append(": ").append(p.explanation()).append('\n');
    }
    sb.append(
        "\nRevise the spec (for example, list a conflicting test under `## Tests to update`),"
            + " then add `noir:ready` again.");
    return sb.toString();
  }
}
