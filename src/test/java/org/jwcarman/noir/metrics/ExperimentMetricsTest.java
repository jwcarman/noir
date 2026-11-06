package org.jwcarman.noir.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.WorkItem;

class ExperimentMetricsTest {
  static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

  static Event ev(String id, long sec, String type, int attempt, Map<String, Object> data) {
    return new Event(1, T0.plusSeconds(sec), id, type, attempt, data);
  }

  static Event grade(String id, int attempt, String result) {
    return ev(id, 10, "grade", attempt, Map.of("result", result));
  }

  static ItemHistory item(String id, State state, int rework, Event... events) {
    var w = WorkItem.sealed(id, "o/r", 1).withState(state).withReworkCount(rework);
    return new ItemHistory(w, List.of(events));
  }

  static Event transition(String id, long sec, State from, State to, Map<String, Object> extra) {
    var data = new java.util.HashMap<String, Object>(extra);
    data.put("from", from.name());
    data.put("to", to.name());
    data.put("trigger", "X");
    return ev(id, sec, "transition", 1, data);
  }

  static Event disposition(String id, boolean draft, String disposition, int pr) {
    return ev(
        id,
        50,
        "pr_disposition",
        1,
        Map.of("pullRequest", pr, "draft", draft, "disposition", disposition));
  }

  @Test
  void emptyStoreHasNoData() {
    var r = ExperimentMetrics.compute(List.of());
    assertThat(r.items()).isZero();
    assertThat(r.firstPassRate()).isNull();
    assertThat(r.meanReworkCycles()).isNull();
    assertThat(r.failureCategories()).isEmpty();
    assertThat(r.totalCostUsd()).isNull();
    assertThat(r.meanCostUsdPerItem()).isNull();
    assertThat(r.medianWallTime()).isNull();
    assertThat(r.medianApprovalTime()).isNull();
    assertThat(r.medianSealTime()).isNull();
    assertThat(r.completed()).isZero();
    assertThat(r.autoMerge().acceptedRate()).isNull();
    assertThat(r.autoMerge().eligible()).isFalse();
  }

  @Test
  void firstPassRateCountsAttemptOneGrades() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item("a", State.PR_OPEN, 0, grade("a", 1, "passed")),
                item("b", State.PR_OPEN, 1, grade("b", 1, "failed"), grade("b", 2, "passed")),
                item("c", State.PR_OPEN, 0, grade("c", 1, "passed"))));
    assertThat(r.firstPassRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(r.byState()).containsEntry(State.PR_OPEN, 3);
  }

  /** With hidden tests off, attempt 1's grade is "skipped" after measurement passed: a pass. */
  @Test
  void skippedGradeOnAttemptOneIsAFirstPass() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item("a", State.PR_OPEN, 0, grade("a", 1, "skipped")),
                item("b", State.PR_OPEN, 0, grade("b", 1, "passed"))));
    assertThat(r.firstPassRate()).isEqualTo(1.0);
  }

  @Test
  void cancelledBeforeGradeIsExcludedFromFirstPass() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item("a", State.PR_OPEN, 0, grade("a", 1, "passed")),
                item("b", State.CANCELLED, 0)));
    assertThat(r.firstPassRate()).isEqualTo(1.0);
    assertThat(r.items()).isEqualTo(2);
  }

  @Test
  void prOpenWithoutGradeIsExcludedFromFirstPass() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item("a", State.PR_OPEN, 0, grade("a", 1, "passed")),
                item("b", State.PR_OPEN, 0),
                item("c", State.FAILED, 2)));
    assertThat(r.firstPassPassed()).isEqualTo(1);
    assertThat(r.firstPassTotal()).isEqualTo(2);
    assertThat(r.firstPassRate()).isEqualTo(0.5);
  }

  @Test
  void reworkCyclesAverageTerminalItems() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item("a", State.PR_OPEN, 2),
                item("b", State.FAILED, 1),
                item("c", State.BUILDING, 9)));
    assertThat(r.meanReworkCycles()).isEqualTo(1.5);
  }

  @Test
  void failureCategoriesCountTransitionCategories() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.PR_OPEN,
                    0,
                    transition(
                        "a", 1, State.BUILDING, State.REWORK, Map.of("category", "BUILD_FAILED")),
                    transition(
                        "a", 2, State.GRADING, State.REWORK, Map.of("category", "BUILD_FAILED")),
                    transition("a", 3, State.GRADING, State.REWORK, Map.of("failure", "NO_OUTPUT")),
                    transition("a", 4, State.GRADING, State.PR_OPEN, Map.of()))));
    assertThat(r.failureCategories())
        .containsEntry(Category.BUILD_FAILED, 2)
        .containsEntry(Category.NO_OUTPUT, 1)
        .hasSize(2);
  }

  @Test
  void costSumsBuilderAndExaminer() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.PR_OPEN,
                    0,
                    ev("a", 1, "build_attempt", 1, Map.of("costUsd", 0.10)),
                    ev("a", 2, "examiner_attempt", 0, Map.of("costUsd", 0.14)),
                    ev("a", 3, "build_attempt", 2, Map.of())),
                item("b", State.SEALED, 0),
                item("c", State.PR_OPEN, 0, ev("c", 1, "build_attempt", 1, Map.of("costUsd", 1)))));
    assertThat(r.totalCostUsd()).isEqualByComparingTo("1.24");
    assertThat(r.meanCostUsdPerItem()).isEqualByComparingTo("0.62");
  }

  @Test
  void wallTimeIsMedianBuildToTerminal() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.PR_OPEN,
                    0,
                    transition("a", 10, State.SEALED, State.BUILDING, Map.of()),
                    transition("a", 70, State.GRADING, State.PR_OPEN, Map.of())),
                item(
                    "b",
                    State.FAILED,
                    0,
                    transition("b", 0, State.SEALED, State.BUILDING, Map.of()),
                    transition("b", 200, State.GRADING, State.FAILED, Map.of())),
                item(
                    "c",
                    State.PR_OPEN,
                    0,
                    transition("c", 0, State.SEALED, State.BUILDING, Map.of()),
                    transition("c", 100, State.GRADING, State.PR_OPEN, Map.of())),
                item(
                    "d",
                    State.BUILDING,
                    0,
                    transition("d", 0, State.SEALED, State.BUILDING, Map.of()))));
    assertThat(r.medianWallTime()).isEqualTo(Duration.ofSeconds(100));
  }

  static Event approved(String id, long sec, long approvedAtSec, long issueCreatedAtSec) {
    return ev(
        id,
        sec,
        "approved",
        0,
        Map.of(
            "approver", "alice",
            "approvedAt", T0.plusSeconds(approvedAtSec).toString(),
            "issueCreatedAt", T0.plusSeconds(issueCreatedAtSec).toString(),
            "bodySha256", "x",
            "title", "t"));
  }

  @Test
  void approvalTimeFromApprovedEvent() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item("a", State.SEALED, 0, approved("a", 500, 300, 0)),
                item("b", State.SEALED, 0, approved("b", 500, 100, 0)),
                item("c", State.SEALED, 0)));
    // medians of 300s and 100s
    assertThat(r.medianApprovalTime()).isEqualTo(Duration.ofSeconds(200));
    assertThat(r.render())
        .contains("Approval time (median) 3m 20s (issue opened \u2192 noir:ready)");
  }

  @Test
  void sealTimeFromApprovedToSealed() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.SEALED,
                    0,
                    approved("a", 10, 5, 0),
                    approved("a", 400, 390, 0),
                    ev("a", 100, "sealed", 0, Map.of("issue", 1)),
                    ev("a", 900, "sealed", 0, Map.of("issue", 1))),
                item("b", State.SEALED, 0, approved("b", 0, 0, 0))));
    assertThat(r.medianSealTime()).isEqualTo(Duration.ofSeconds(90));
    assertThat(r.render()).contains("Seal time (median)     1m 30s (first approval \u2192 sealed)");
  }

  @Test
  void sealRejectionsByReason() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.DRAFT,
                    0,
                    ev(
                        "a",
                        1,
                        "seal_rejected",
                        0,
                        Map.of("reason", "suite_too_weak", "problems", 2)),
                    ev(
                        "a",
                        2,
                        "seal_rejected",
                        0,
                        Map.of("reason", "edited_after_approval", "problems", 1)),
                    ev(
                        "a",
                        3,
                        "seal_rejected",
                        0,
                        Map.of("reason", "suite_too_weak", "problems", 1)))));
    assertThat(r.sealRejections())
        .containsExactly(Map.entry("edited_after_approval", 1), Map.entry("suite_too_weak", 2));
    assertThat(r.render())
        .contains("Seal rejections        edited_after_approval 1, suite_too_weak 2");
    assertThat(r.toMap()).containsKeys("medianApprovalTime", "medianSealTime", "sealRejections");
  }

  @Test
  void oldItemsWithoutApprovedEventShowNa() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "old",
                    State.SEALED,
                    0,
                    ev("old", 0, "draft_created", 0, Map.of()),
                    ev("old", 120, "seal_requested", 0, Map.of()))));
    assertThat(r.medianApprovalTime()).isNull();
    assertThat(r.medianSealTime()).isNull();
    assertThat(r.render())
        .contains("Approval time (median) n/a")
        .contains("Seal time (median)     n/a")
        .contains("Seal rejections        none");
    assertThat(r.toMap().get("medianApprovalTime")).isNull();
  }

  @Test
  void dispositionCountsExcludeDraftPrs() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.PR_OPEN,
                    0,
                    disposition("a", false, "accepted", 1),
                    disposition("a", true, "rejected", 2)),
                item("b", State.PR_OPEN, 0, disposition("b", false, "modified", 3)),
                item("c", State.PR_OPEN, 0, disposition("c", false, "rejected", 4))));
    assertThat(r.accepted()).isEqualTo(1);
    assertThat(r.modified()).isEqualTo(1);
    assertThat(r.rejected()).isEqualTo(1);
    assertThat(r.completed()).isEqualTo(3);
  }

  @Test
  void holdoutDefectsCountGradeDefectsAndDisagreements() {
    Event v1 = ev("a", 1, "audit_verdict", 0, Map.of("test", "T1", "verdict", "disagree"));
    Event v2 = ev("a", 2, "audit_verdict", 0, Map.of("test", "T1", "verdict", "agree"));
    Event v3 = ev("a", 3, "audit_verdict", 0, Map.of("test", "T2", "verdict", "disagree"));
    var r =
        ExperimentMetrics.compute(
            List.of(item("a", State.PR_OPEN, 0, grade("a", 1, "holdout_defect"), v1, v2, v3)));
    assertThat(r.holdoutDefects()).isEqualTo(2);
  }

  private static List<ItemHistory> accepted(int n, int modified) {
    var l = new ArrayList<ItemHistory>();
    for (int i = 0; i < n; i++) {
      var id = "i" + i;
      l.add(
          item(
              id,
              State.PR_OPEN,
              0,
              disposition(id, false, i < modified ? "modified" : "accepted", i + 1)));
    }
    return l;
  }

  @Test
  void autoMergeEligibleAtTwentyAcceptedNoDefects() {
    var r = ExperimentMetrics.compute(accepted(20, 0));
    assertThat(r.autoMerge().eligible()).isTrue();
    assertThat(r.autoMerge().completed()).isEqualTo(20);
    assertThat(r.autoMerge().required()).isEqualTo(20);
    assertThat(r.autoMerge().requiredRate()).isEqualTo(0.90);
    assertThat(r.render()).contains("Auto-merge             eligible");
  }

  @Test
  void autoMergeNotEligibleBelowNinetyPercent() {
    var r = ExperimentMetrics.compute(accepted(20, 3));
    assertThat(r.autoMerge().eligible()).isFalse();
    assertThat(r.autoMerge().acceptedRate()).isEqualTo(0.85);
    assertThat(r.render()).contains("not eligible: 85% accepted (need 90%)");
    assertThat(ExperimentMetrics.compute(accepted(5, 0)).render())
        .contains("not eligible: 5/20 completed items");
  }

  @Test
  void autoMergeTextNeverRoundsUpToThreshold() {
    var r = ExperimentMetrics.compute(accepted(29, 3));
    assertThat(r.autoMerge().eligible()).isFalse();
    assertThat(r.render()).contains("not eligible: 89% accepted (need 90%)");
  }

  @Test
  void exactPercentagesAreNotRoundedDownByFloatingPointError() {
    // 57/100 as a double times 100 is 56.99999999999999
    var r = ExperimentMetrics.compute(accepted(100, 43));
    assertThat(r.render()).contains("not eligible: 57% accepted (need 90%)");
  }

  @Test
  void itemsWithoutNewEventsAreCountedButExcludedFromDerivedMetrics() {
    var r = ExperimentMetrics.compute(List.of(item("old", State.PR_OPEN, 0)));
    assertThat(r.items()).isEqualTo(1);
    assertThat(r.byState()).containsEntry(State.PR_OPEN, 1);
    assertThat(r.medianApprovalTime()).isNull();
    assertThat(r.medianSealTime()).isNull();
    assertThat(r.sealRejections()).isEmpty();
    assertThat(r.medianWallTime()).isNull();
    assertThat(r.totalCostUsd()).isNull();
    assertThat(r.completed()).isZero();
    assertThat(r.render()).contains("Cost                   n/a");
  }

  @Test
  void valuesLineUpInOneColumn() {
    var lines = ExperimentMetrics.compute(List.of()).render().lines().skip(1).toList();
    assertThat(lines).isNotEmpty();
    for (var line : lines) {
      // "  " + label padded to the widest label (22) + " " + value
      assertThat(line.charAt(24)).as(line).isEqualTo(' ');
      assertThat(line.charAt(25)).as(line).isNotEqualTo(' ');
    }
  }

  @Test
  void rendersExampleBlock() {
    var r =
        ExperimentMetrics.compute(
            List.of(
                item(
                    "a",
                    State.PR_OPEN,
                    0,
                    grade("a", 1, "passed"),
                    ev("a", 1, "build_attempt", 1, Map.of("costUsd", new BigDecimal("0.24"))),
                    transition("a", 0, State.SEALED, State.BUILDING, Map.of()),
                    transition("a", 73, State.GRADING, State.PR_OPEN, Map.of()))));
    assertThat(r.render())
        .isEqualTo(
            """
            Experiment (1 item)
              First-pass rate        100% (1/1)
              Rework cycles          0.0 per item
              Failure categories     none
              Seal rejections        none
              Cost                   $0.24 total, $0.24 per item
              Wall time (median)     1m 13s
              Approval time (median) n/a
              Seal time (median)     n/a
              PR disposition         0 accepted, 0 modified, 0 rejected
              Holdout defects        0
              Auto-merge             not eligible: 0/20 completed items
            """);
  }
}
