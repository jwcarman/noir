package org.jwcarman.noir.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.lifecycle.SealRejection;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.metrics.ExperimentReport.AutoMerge;
import org.jwcarman.noir.store.Event;

/** Folds item event logs into experiment metrics. Pure: no I/O. */
public final class ExperimentMetrics {
  private static final java.util.Set<Object> PASSED_GRADES = java.util.Set.of("passed", "skipped");

  static final int REQUIRED_COMPLETED = 20;
  static final double REQUIRED_RATE = 0.90;

  private ExperimentMetrics() {}

  public static ExperimentReport compute(List<ItemHistory> items) {
    var byState = new EnumMap<State, Integer>(State.class);
    var categories = new EnumMap<Category, Integer>(Category.class);
    int gradedItems = 0;
    int firstPassed = 0;
    int terminalItems = 0;
    long reworkSum = 0;
    BigDecimal totalCost = null;
    int itemsWithCost = 0;
    var wall = new ArrayList<Duration>();
    var approval = new ArrayList<Duration>();
    var sealTime = new ArrayList<Duration>();
    var rejections = new HashMap<String, Integer>();
    int accepted = 0;
    int modified = 0;
    int rejected = 0;
    int holdoutDefects = 0;
    int specProblemItems = 0;

    for (var h : items) {
      var state = h.item().state();
      byState.merge(state, 1, Integer::sum);
      var events = h.events();

      boolean terminal = state == State.PR_OPEN || state == State.FAILED;
      if (terminal) {
        terminalItems++;
        reworkSum += h.item().reworkCount();
      }

      // First pass counts items graded on attempt 1, plus FAILED items (failed before any grade).
      // A "skipped" grade (hidden tests off) follows a passed measurement, so it is a pass.
      boolean firstGrade =
          events.stream().anyMatch(e -> e.type().equals("grade") && e.attempt() == 1);
      if (firstGrade || state == State.FAILED) {
        gradedItems++;
        boolean passed =
            events.stream()
                .anyMatch(e ->
                    e.type().equals("grade")
                        && e.attempt() == 1
                        && PASSED_GRADES.contains(e.data().get("result")));
        if (passed) {
          firstPassed++;
        }
      }

      for (var e : events) {
        if (e.type().equals("transition")) {
          category(e).ifPresent(c -> categories.merge(c, 1, Integer::sum));
        }
      }

      BigDecimal cost = null;
      for (var e : events) {
        if (e.type().equals("build_attempt") || e.type().equals("examiner_attempt")) {
          var c = decimal(e.data().get("costUsd"));
          if (c != null) {
            cost = cost == null ? c : cost.add(c);
          }
        }
      }
      if (cost != null) {
        totalCost = totalCost == null ? cost : totalCost.add(cost);
        itemsWithCost++;
      }

      wallTime(events).ifPresent(wall::add);
      approvalTime(events).ifPresent(approval::add);
      sealTime(events).ifPresent(sealTime::add);
      for (var e : events) {
        if (e.type().equals("seal_rejected") && e.data().get("reason") != null) {
          rejections.merge(String.valueOf(e.data().get("reason")), 1, Integer::sum);
        }
      }
      if (events.stream().anyMatch(e -> e.type().equals("spec_problems"))) {
        specProblemItems++;
      }

      var latestByPr = new HashMap<String, Event>();
      for (var e : events) {
        if (e.type().equals("pr_disposition") && !Boolean.TRUE.equals(e.data().get("draft"))) {
          latestByPr.put(String.valueOf(e.data().get("pullRequest")), e);
        }
      }
      for (var e : latestByPr.values()) {
        switch (String.valueOf(e.data().get("disposition"))) {
          case "accepted" -> accepted++;
          case "modified" -> modified++;
          case "rejected" -> rejected++;
          default -> {}
        }
      }

      holdoutDefects +=
          (int)
              events.stream()
                  .filter(e ->
                      e.type().equals("grade") && "holdout_defect".equals(e.data().get("result")))
                  .count();
      var verdicts = new HashMap<String, String>();
      for (var e : events) {
        if (e.type().equals("audit_verdict") && e.data().get("test") != null) {
          verdicts.put(
              String.valueOf(e.data().get("test")), String.valueOf(e.data().get("verdict")));
        }
      }
      holdoutDefects += (int) verdicts.values().stream().filter("disagree"::equals).count();
    }

    int completed = accepted + modified + rejected;
    Double acceptedRate = completed == 0 ? null : (double) accepted / completed;
    boolean eligible =
        completed >= REQUIRED_COMPLETED
            && acceptedRate != null
            && acceptedRate >= REQUIRED_RATE
            && holdoutDefects == 0;

    return new ExperimentReport(
        items.size(),
        byState,
        gradedItems == 0 ? null : (double) firstPassed / gradedItems,
        firstPassed,
        gradedItems,
        terminalItems == 0 ? null : (double) reworkSum / terminalItems,
        categories,
        totalCost,
        totalCost == null
            ? null
            : totalCost.divide(BigDecimal.valueOf(itemsWithCost), 6, RoundingMode.HALF_UP),
        median(wall),
        median(approval),
        median(sealTime),
        sealRejections(rejections),
        specProblemItems,
        accepted,
        modified,
        rejected,
        completed,
        holdoutDefects,
        new AutoMerge(
            completed, REQUIRED_COMPLETED, acceptedRate, REQUIRED_RATE, holdoutDefects, eligible));
  }

  private static Optional<Category> category(Event e) {
    var raw = e.data().getOrDefault("category", e.data().get("failure"));
    if (raw == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Category.valueOf(raw.toString()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof Number n) {
      try {
        return new BigDecimal(n.toString());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static boolean transitionTo(Event e, State... targets) {
    if (!e.type().equals("transition")) {
      return false;
    }
    for (var t : targets) {
      if (t.name().equals(e.data().get("to"))) {
        return true;
      }
    }
    return false;
  }

  private static Optional<Duration> wallTime(List<Event> events) {
    Instant start = null;
    for (var e : events) {
      if (start == null
          && transitionTo(e, State.BUILDING)
          && State.SEALED.name().equals(e.data().get("from"))) {
        start = e.ts();
      } else if (start != null && transitionTo(e, State.PR_OPEN, State.FAILED)) {
        return Optional.of(Duration.between(start, e.ts()));
      }
    }
    return Optional.empty();
  }

  /** Issue creation to the first approval ({@code noir:ready}), from the first approved event. */
  private static Optional<Duration> approvalTime(List<Event> events) {
    var approved = firstOfType(events, "approved");
    if (approved.isEmpty()) {
      return Optional.empty();
    }
    try {
      var data = approved.get().data();
      return Optional.of(
          Duration.between(
              Instant.parse(String.valueOf(data.get("issueCreatedAt"))),
              Instant.parse(String.valueOf(data.get("approvedAt")))));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** First approved event to the first sealed event. */
  private static Optional<Duration> sealTime(List<Event> events) {
    var approved = firstOfType(events, "approved");
    var sealed = firstOfType(events, "sealed");
    if (approved.isEmpty() || sealed.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Duration.between(approved.get().ts(), sealed.get().ts()));
  }

  private static Optional<Event> firstOfType(List<Event> events, String type) {
    return events.stream().filter(e -> e.type().equals(type)).findFirst();
  }

  /** Rejection counts keyed by reason, in {@link SealRejection} order, zero counts omitted. */
  private static Map<String, Integer> sealRejections(Map<String, Integer> counts) {
    var ordered = new LinkedHashMap<String, Integer>();
    for (var r : SealRejection.values()) {
      var n = counts.get(r.eventValue());
      if (n != null && n > 0) {
        ordered.put(r.eventValue(), n);
      }
    }
    return ordered;
  }

  private static Duration median(List<Duration> values) {
    if (values.isEmpty()) {
      return null;
    }
    var sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1
        ? sorted.get(n / 2)
        : sorted.get(n / 2 - 1).plus(sorted.get(n / 2)).dividedBy(2);
  }
}
