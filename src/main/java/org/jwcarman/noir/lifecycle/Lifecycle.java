package org.jwcarman.noir.lifecycle;

import static org.jwcarman.noir.lifecycle.SideEffect.*;

import java.util.List;
import java.util.Optional;
import org.jwcarman.noir.store.WorkItem;

/** The work-item state machine (spec §4.5). Pure: no I/O. */
public final class Lifecycle {
  private Lifecycle() {}

  public static Transition apply(WorkItem item, Trigger trigger, int maxReworks) {
    var state = item.state();
    if (state.terminal()) {
      throw new IllegalTransitionException(state, trigger);
    }
    return switch (trigger) {
      case Trigger.Approved a when state == State.DRAFT && item.attempt() > 0 ->
          // Back in draft after building (a spec problem): start over from a fresh seal.
          to(
              item.withState(State.SEALING).withAttempt(0).withReworkCount(0),
              ARCHIVE_ATTEMPTS,
              APPLY_LABEL);
      case Trigger.Approved a when state == State.DRAFT ->
          to(item.withState(State.SEALING), APPLY_LABEL);
      case Trigger.SealPassed s when state == State.SEALING ->
          to(item.withState(State.SEALED), APPLY_LABEL);
      case Trigger.SealRejected s when state == State.SEALING ->
          to(item.withState(State.DRAFT), REJECT_APPROVAL, APPLY_LABEL);
      case Trigger.Pickup p when state == State.SEALED || state == State.REWORK ->
          to(
              item.withState(State.BUILDING).withAttempt(item.attempt() + 1),
              START_ATTEMPT,
              APPLY_LABEL);
      case Trigger.AgentExited a when state == State.BUILDING ->
          a.producedCommits()
              ? to(item.withState(State.MEASURING), EXTRACT_BUNDLE)
              : fail(item, Category.NO_OUTPUT, maxReworks);
      case Trigger.SpecProblemReported s when state == State.BUILDING ->
          to(item.withState(State.DRAFT), REJECT_APPROVAL, APPLY_LABEL);
      case Trigger.MeasurementFailed m
          when state == State.MEASURING && isMeasurementCategory(m.category()) ->
          fail(item, m.category(), maxReworks);
      case Trigger.MeasurementPassed m when state == State.MEASURING ->
          to(item.withState(State.GRADING), EXPORT_ARTIFACTS);
      case Trigger.GradePassed g when state == State.GRADING ->
          to(item.withState(State.PR_OPEN), OPEN_PR, APPLY_LABEL);
      case Trigger.GradeFailed g when state == State.GRADING ->
          fail(item, Category.BEHAVIOR_FAILURE, maxReworks);
      case Trigger.HoldoutDefect h when state == State.GRADING ->
          to(block(item, BlockReason.HOLDOUT_DEFECT), APPLY_LABEL);
      case Trigger.InfraBlocked b when state.active() ->
          to(block(item, BlockReason.INFRASTRUCTURE), STOP_CONTAINERS, APPLY_LABEL);
      case Trigger.SecretFound s when state.active() ->
          to(block(item, BlockReason.SECRET_FOUND), APPLY_LABEL);
      case Trigger.IssueClosed c ->
          to(item.withState(State.CANCELLED), STOP_CONTAINERS, APPLY_LABEL);
      case Trigger.CancelRequested c ->
          to(item.withState(State.CANCELLED), STOP_CONTAINERS, APPLY_LABEL);
      case Trigger.ResumeRequested r
          when state == State.BLOCKED && item.blockReason() != BlockReason.HOLDOUT_DEFECT ->
          to(item.withState(item.blockedPhase()).withoutBlock(), APPLY_LABEL);
      default -> throw new IllegalTransitionException(state, trigger);
    };
  }

  private static boolean isMeasurementCategory(Category c) {
    return c == Category.BUILD_FAILED
        || c == Category.CONTRACT_VIOLATION
        || c == Category.QUALITY_SHORTFALL;
  }

  private static WorkItem block(WorkItem item, BlockReason reason) {
    return item.withState(State.BLOCKED).withBlock(reason, item.state());
  }

  private static Transition fail(WorkItem item, Category category, int maxReworks) {
    if (item.reworkCount() < maxReworks) {
      return new Transition(
          item.withState(State.REWORK).withReworkCount(item.reworkCount() + 1),
          Optional.of(category),
          List.of(WRITE_FEEDBACK, APPLY_LABEL));
    }
    return new Transition(
        item.withState(State.FAILED),
        Optional.of(category),
        List.of(OPEN_DRAFT_PR_OR_COMMENT, APPLY_LABEL));
  }

  private static Transition to(WorkItem next, SideEffect... effects) {
    return new Transition(next, Optional.empty(), List.of(effects));
  }
}
