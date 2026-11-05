package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class TransitionsTest {
  @TempDir Path home;
  final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
  Store store;
  WorkItem item;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(home));
    item = WorkItem.sealed("w1", "o/r", 1).withState(State.MEASURING);
  }

  private java.util.Map<String, Object> persist(State to, Trigger trigger) {
    Transitions.persist(store, clock, item, item.withState(to), trigger);
    return store.readEvents("w1").getLast().data();
  }

  @Test
  void reworkFromMeasurementRecordsItsCategory() {
    var data = persist(State.REWORK, new Trigger.MeasurementFailed(Category.QUALITY_SHORTFALL));
    assertThat(data).containsEntry("category", "QUALITY_SHORTFALL").containsEntry("to", "REWORK");
  }

  @Test
  void gradeFailureRecordsBehaviorFailure() {
    item = item.withState(State.GRADING);
    assertThat(persist(State.FAILED, new Trigger.GradeFailed()))
        .containsEntry("category", "BEHAVIOR_FAILURE");
  }

  @Test
  void agentExitWithoutCommitsRecordsNoOutput() {
    item = item.withState(State.BUILDING);
    assertThat(persist(State.REWORK, new Trigger.AgentExited(false)))
        .containsEntry("category", "NO_OUTPUT");
  }

  @Test
  void nonFailureTransitionHasNoCategory() {
    assertThat(persist(State.GRADING, new Trigger.MeasurementPassed()))
        .doesNotContainKey("category");
    assertThat(Transitions.categoryOf(new Trigger.AgentExited(true))).isEmpty();
  }

  @Test
  void sealRejectedRecordsReason() {
    item = item.withState(State.SEALING);
    var data =
        persist(
            State.DRAFT,
            new Trigger.SealRejected(org.jwcarman.noir.lifecycle.SealRejection.SUITE_TOO_WEAK));
    assertThat(data).containsEntry("reason", "suite_too_weak").containsEntry("to", "DRAFT");
  }
}
