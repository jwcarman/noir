package org.jwcarman.noir.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jwcarman.noir.measure.TestInventory;
import org.jwcarman.noir.spec.QualityBar;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Runs;

/**
 * The "Why it failed" section of a failed item's draft pull request and failure comment: one line
 * per attempt (identical consecutive lines collapsed), then the existing tests that failed and that
 * the spec does not let change. Built only from the item's events for its latest run; never holdout
 * test names or build logs.
 */
final class FailureExplanation {
  static final int MAX_TESTS = 20;

  private FailureExplanation() {}

  static String render(List<Event> allEvents, QualityBar bar) {
    return render(allEvents, bar, 0, null);
  }

  /**
   * {@code finalCategory} (or null) is the category of {@code finalAttempt}, whose transition is
   * not yet in {@code allEvents} when the failure is delivered.
   */
  static String render(
      List<Event> allEvents, QualityBar bar, int finalAttempt, String finalCategory) {
    var events = Runs.latest(allEvents);
    var categories = new TreeMap<Integer, String>();
    for (var e : events) {
      if (e.type().equals("transition") && e.data().get("category") instanceof String c) {
        categories.put(e.attempt(), c);
      }
    }
    if (finalCategory != null) {
      categories.put(finalAttempt, finalCategory);
    }
    var sb = new StringBuilder("### Why it failed\n\n");
    int start = -1;
    String previous = null;
    int last = -1;
    for (var entry : categories.entrySet()) {
      var text = line(entry.getValue(), entry.getKey(), events, bar);
      if (text.equals(previous) && entry.getKey() == last + 1) {
        last = entry.getKey();
        continue;
      }
      if (previous != null) {
        append(sb, start, last, previous);
      }
      start = entry.getKey();
      last = start;
      previous = text;
    }
    if (previous != null) {
      append(sb, start, last, previous);
    }
    var locked = new TreeSet<String>();
    for (var e : events) {
      if (e.type().equals("measurement")
          && e.data().get("lockedTestsFailed") instanceof List<?> l) {
        l.forEach(t -> locked.add(clean(String.valueOf(t))));
      }
    }
    if (!locked.isEmpty()) {
      var tests = new ArrayList<>(locked);
      var shown =
          tests.stream().limit(MAX_TESTS).map(t -> "`" + t + "`").collect(Collectors.joining(", "));
      var more = tests.size() - MAX_TESTS;
      var files =
          tests.stream()
              .map(TestInventory::sourcePath)
              .distinct()
              .sorted()
              .map(f -> "`" + f + "`")
              .collect(Collectors.joining(", "));
      sb.append("\nExisting tests that failed: ")
          .append(shown)
          .append(more > 0 ? ", and " + more + " more" : "")
          .append(
              ". This spec doesn't allow them to change. If the change is meant to alter what they"
                  + " check, list ")
          .append(files)
          .append(" under `## Tests to update`, then add `noir:ready` again.\n");
    }
    return sb.toString();
  }

  private static void append(StringBuilder sb, int from, int to, String text) {
    sb.append(from == to ? "- Attempt " + from : "- Attempts " + from + "–" + to)
        .append(": ")
        .append(text)
        .append('\n');
  }

  private static String line(String category, int attempt, List<Event> events, QualityBar bar) {
    var measured = data(events, "measurement", attempt);
    return switch (category) {
      case "BUILD_FAILED" -> "the build failed.";
      case "CONTRACT_VIOLATION" ->
          measured.get("violation") instanceof String v
              ? "contract violation: " + text(v)
              : "contract violation.";
      case "QUALITY_SHORTFALL" -> "quality shortfall: " + shortfall(measured, bar) + ".";
      case "BEHAVIOR_FAILURE" ->
          "hidden tests failed: " + results(data(events, "grade", attempt)) + ".";
      case "NO_OUTPUT" -> noOutput(data(events, "build_attempt", attempt));
      default -> clean(category.toLowerCase(Locale.ROOT)) + ".";
    };
  }

  private static String shortfall(Map<String, Object> m, QualityBar bar) {
    var parts = new ArrayList<String>();
    if (m.get("lineCoverage") instanceof Number c && c.doubleValue() < bar.lineCoverage()) {
      parts.add("line coverage " + pct(c.doubleValue()) + " (bar " + pct(bar.lineCoverage()) + ")");
    }
    if (m.get("mutationScore") instanceof Number s && s.doubleValue() < bar.mutationScore()) {
      parts.add(
          "mutation score " + pct(s.doubleValue()) + " (bar " + pct(bar.mutationScore()) + ")");
    }
    if (m.get("maxCrap") instanceof Number c && c.doubleValue() > bar.maxCrap()) {
      parts.add(
          String.format(Locale.ROOT, "max CRAP %.1f (bar %d)", c.doubleValue(), bar.maxCrap()));
    }
    return parts.isEmpty() ? "below the quality bar" : String.join(", ", parts);
  }

  private static String results(Map<String, Object> grade) {
    if (!(grade.get("results") instanceof List<?> list) || list.isEmpty()) {
      return "no per-requirement results";
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(r -> (Map<?, ?>) r)
        .map(r ->
            clean(String.valueOf(r.get("requirement")))
                + " "
                + r.get("passed")
                + "/"
                + r.get("total")
                + " passed")
        .collect(Collectors.joining(", "));
  }

  private static String noOutput(Map<String, Object> built) {
    var text = "no commits; the builder stopped without changing anything.";
    if (!(built.get("lastMessage") instanceof String message) || message.isBlank()) {
      return text;
    }
    var quoted =
        message.strip().lines().map(l -> "  > " + text(l)).collect(Collectors.joining("\n"));
    return text + "\n  The builder's last message:\n" + quoted;
  }

  private static Map<String, Object> data(List<Event> events, String type, int attempt) {
    Map<String, Object> found = Map.of();
    for (var e : events) {
      if (e.type().equals(type) && e.attempt() == attempt) {
        found = e.data();
      }
    }
    return found;
  }

  private static String pct(double v) {
    return String.format(Locale.ROOT, "%.1f%%", v * 100);
  }

  /** Free text on one line: control characters become spaces. */
  private static String text(String s) {
    return s.replaceAll("\\p{Cntrl}+", " ").strip();
  }

  /** One line, safe in Markdown code spans: control characters to spaces, backticks dropped. */
  private static String clean(String s) {
    return s.replaceAll("\\p{Cntrl}+", " ").replace("`", "").strip();
  }
}
