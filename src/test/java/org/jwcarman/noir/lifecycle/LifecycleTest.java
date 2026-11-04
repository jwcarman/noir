package org.jwcarman.noir.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.noir.lifecycle.SideEffect.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.jwcarman.noir.store.WorkItem;

class LifecycleTest {
  static final String ID = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W";
  static final int MAX = 3;

  static WorkItem atState(State s) {
    return WorkItem.sealed(ID, "o/r", 1).withState(s).withAttempt(1);
  }

  static Transition apply(WorkItem item, Trigger t) {
    return Lifecycle.apply(item, t, MAX);
  }

  @Test
  void pickupFromSealedStartsFirstAttempt() {
    var t = apply(WorkItem.sealed(ID, "o/r", 1), new Trigger.Pickup());
    assertThat(t.next().state()).isEqualTo(State.BUILDING);
    assertThat(t.next().attempt()).isEqualTo(1);
    assertThat(t.effects()).containsExactly(START_ATTEMPT, APPLY_LABEL);
    assertThat(t.failure()).isEmpty();
  }

  @Test
  void pickupFromReworkIncrementsAttempt() {
    var t = apply(atState(State.REWORK).withAttempt(2), new Trigger.Pickup());
    assertThat(t.next().state()).isEqualTo(State.BUILDING);
    assertThat(t.next().attempt()).isEqualTo(3);
    assertThat(t.effects()).containsExactly(START_ATTEMPT, APPLY_LABEL);
  }

  @Test
  void agentExitWithCommitsGoesToMeasuring() {
    var t = apply(atState(State.BUILDING), new Trigger.AgentExited(true));
    assertThat(t.next().state()).isEqualTo(State.MEASURING);
    assertThat(t.effects()).containsExactly(EXTRACT_BUNDLE);
  }

  @Test
  void agentExitWithoutCommitsIsNoOutputFailure() {
    var t = apply(atState(State.BUILDING), new Trigger.AgentExited(false));
    assertThat(t.next().state()).isEqualTo(State.REWORK);
    assertThat(t.next().reworkCount()).isEqualTo(1);
    assertThat(t.failure()).contains(Category.NO_OUTPUT);
    assertThat(t.effects()).containsExactly(WRITE_FEEDBACK, APPLY_LABEL);
  }

  @ParameterizedTest
  @EnumSource(
      value = Category.class,
      names = {"BUILD_FAILED", "CONTRACT_VIOLATION", "QUALITY_SHORTFALL"})
  void measurementFailureReworks(Category c) {
    var t = apply(atState(State.MEASURING), new Trigger.MeasurementFailed(c));
    assertThat(t.next().state()).isEqualTo(State.REWORK);
    assertThat(t.failure()).contains(c);
    assertThat(t.effects()).containsExactly(WRITE_FEEDBACK, APPLY_LABEL);
  }

  @Test
  void measurementPassGoesToGrading() {
    var t = apply(atState(State.MEASURING), new Trigger.MeasurementPassed());
    assertThat(t.next().state()).isEqualTo(State.GRADING);
    assertThat(t.effects()).containsExactly(EXPORT_ARTIFACTS);
  }

  @Test
  void gradePassOpensPr() {
    var t = apply(atState(State.GRADING), new Trigger.GradePassed());
    assertThat(t.next().state()).isEqualTo(State.PR_OPEN);
    assertThat(t.effects()).containsExactly(OPEN_PR, APPLY_LABEL);
  }

  @Test
  void gradeFailureReworks() {
    var t = apply(atState(State.GRADING), new Trigger.GradeFailed());
    assertThat(t.next().state()).isEqualTo(State.REWORK);
    assertThat(t.failure()).contains(Category.BEHAVIOR_FAILURE);
  }

  @Test
  void failureWithBudgetExhaustedFails() {
    var t = apply(atState(State.GRADING).withReworkCount(MAX), new Trigger.GradeFailed());
    assertThat(t.next().state()).isEqualTo(State.FAILED);
    assertThat(t.next().reworkCount()).isEqualTo(MAX);
    assertThat(t.failure()).contains(Category.BEHAVIOR_FAILURE);
    assertThat(t.effects()).containsExactly(OPEN_DRAFT_PR_OR_COMMENT, APPLY_LABEL);
  }

  @Test
  void holdoutDefectBlocks() {
    var t = apply(atState(State.GRADING), new Trigger.HoldoutDefect());
    assertThat(t.next().state()).isEqualTo(State.BLOCKED);
    assertThat(t.next().blockReason()).isEqualTo(BlockReason.HOLDOUT_DEFECT);
    assertThat(t.next().blockedPhase()).isEqualTo(State.GRADING);
    assertThat(t.effects()).containsExactly(APPLY_LABEL);
  }

  @ParameterizedTest
  @EnumSource(
      value = State.class,
      names = {"BUILDING", "MEASURING", "GRADING"})
  void infraFailureBlocksActivePhase(State s) {
    var t = apply(atState(s), new Trigger.InfraBlocked());
    assertThat(t.next().state()).isEqualTo(State.BLOCKED);
    assertThat(t.next().blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(t.next().blockedPhase()).isEqualTo(s);
    assertThat(t.effects()).containsExactly(STOP_CONTAINERS, APPLY_LABEL);
  }

  @ParameterizedTest
  @EnumSource(
      value = State.class,
      names = {"BUILDING", "MEASURING", "GRADING"})
  void secretFoundBlocks(State s) {
    var t = apply(atState(s), new Trigger.SecretFound());
    assertThat(t.next().state()).isEqualTo(State.BLOCKED);
    assertThat(t.next().blockReason()).isEqualTo(BlockReason.SECRET_FOUND);
    assertThat(t.next().blockedPhase()).isEqualTo(s);
    assertThat(t.effects()).containsExactly(APPLY_LABEL);
  }

  @ParameterizedTest
  @EnumSource(
      value = State.class,
      names = {"SEALED", "REWORK", "BUILDING", "MEASURING", "GRADING", "BLOCKED"})
  void issueClosedOrCancelCancels(State s) {
    for (var trigger : List.of(new Trigger.IssueClosed(), new Trigger.CancelRequested())) {
      var t = apply(atState(s), trigger);
      assertThat(t.next().state()).isEqualTo(State.CANCELLED);
      assertThat(t.effects()).containsExactly(STOP_CONTAINERS, APPLY_LABEL);
    }
  }

  @Test
  void infraBlockResumesToSamePhase() {
    var blocked = apply(atState(State.MEASURING), new Trigger.InfraBlocked()).next();
    var t = apply(blocked, new Trigger.ResumeRequested());
    assertThat(t.next().state()).isEqualTo(State.MEASURING);
    assertThat(t.next().blockReason()).isNull();
    assertThat(t.next().blockedPhase()).isNull();
    assertThat(t.effects()).containsExactly(APPLY_LABEL);
  }

  @Test
  void secretBlockCanResume() {
    var blocked = apply(atState(State.GRADING), new Trigger.SecretFound()).next();
    assertThat(apply(blocked, new Trigger.ResumeRequested()).next().state())
        .isEqualTo(State.GRADING);
  }

  @Test
  void holdoutDefectCannotResume() {
    var blocked = apply(atState(State.GRADING), new Trigger.HoldoutDefect()).next();
    assertThatThrownBy(() -> apply(blocked, new Trigger.ResumeRequested()))
        .isInstanceOf(IllegalTransitionException.class);
  }

  @Test
  void reworkThenFailAfterBudget() {
    var item = WorkItem.sealed(ID, "o/r", 1);
    for (int i = 0; i < 3; i++) {
      item = apply(item, new Trigger.Pickup()).next();
      item = apply(item, new Trigger.AgentExited(true)).next();
      item = apply(item, new Trigger.MeasurementPassed()).next();
      var t = apply(item, new Trigger.GradeFailed());
      assertThat(t.next().state()).isEqualTo(State.REWORK);
      assertThat(t.failure()).contains(Category.BEHAVIOR_FAILURE);
      item = t.next();
    }
    item = apply(item, new Trigger.Pickup()).next();
    assertThat(item.attempt()).isEqualTo(4);
    item = apply(item, new Trigger.AgentExited(false)).next();
    assertThat(item.state()).isEqualTo(State.FAILED);
  }

  @Test
  void terminalStatesRejectEverything() {
    for (var s : List.of(State.PR_OPEN, State.FAILED, State.CANCELLED)) {
      assertThatThrownBy(() -> apply(atState(s), new Trigger.Pickup()))
          .isInstanceOf(IllegalTransitionException.class);
      assertThatThrownBy(() -> apply(atState(s), new Trigger.CancelRequested()))
          .isInstanceOf(IllegalTransitionException.class);
    }
  }

  @Test
  void measurementCannotReportBehaviorFailure() {
    assertThatThrownBy(() ->
            apply(
                atState(State.MEASURING), new Trigger.MeasurementFailed(Category.BEHAVIOR_FAILURE)))
        .isInstanceOf(IllegalTransitionException.class);
  }

  @Test
  void outOfOrderTriggersRejected() {
    assertThatThrownBy(() -> apply(atState(State.SEALED), new Trigger.GradePassed()))
        .isInstanceOf(IllegalTransitionException.class)
        .hasMessageContaining("SEALED")
        .hasMessageContaining("GradePassed");
    assertThatThrownBy(() -> apply(atState(State.BUILDING), new Trigger.Pickup()))
        .isInstanceOf(IllegalTransitionException.class);
    assertThatThrownBy(() -> apply(atState(State.SEALED), new Trigger.InfraBlocked()))
        .isInstanceOf(IllegalTransitionException.class);
  }
}
