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

  @Test
  void reapprovalArchivesAttempts() throws java.io.IOException {
    var dir = store.itemDir("w1");
    java.nio.file.Files.createDirectories(dir.resolve("attempts/1"));
    java.nio.file.Files.writeString(dir.resolve("attempts/1/x"), "one");
    item = item.withState(State.DRAFT).withAttempt(2).withReworkCount(1);
    store.writeWorkItem(item);
    var labels = new java.util.ArrayList<State>();

    Transitions.applyExternal(store, clock, (i, s) -> labels.add(s), item, new Trigger.Approved());

    assertThat(dir.resolve("runs/1/attempts/1/x")).hasContent("one");
    assertThat(dir.resolve("attempts")).doesNotExist();
    assertThat(store.readWorkItem("w1").attempt()).isZero();
    assertThat(labels).containsExactly(State.SEALING);
    var events = store.readEvents("w1");
    assertThat(events)
        .extracting(org.jwcarman.noir.store.Event::type)
        .containsExactly("restarted", "transition");
    assertThat(events.getFirst().data()).containsEntry("run", 1);

    java.nio.file.Files.createDirectories(dir.resolve("attempts/1"));
    var again = store.readWorkItem("w1").withState(State.DRAFT).withAttempt(1);
    Transitions.applyExternal(store, clock, (i, s) -> {}, again, new Trigger.Approved());
    assertThat(dir.resolve("runs/2/attempts/1")).isDirectory();
  }

  @Test
  void reapprovalWithoutAttemptsDirStillRestarts() {
    item = item.withState(State.DRAFT).withAttempt(2);
    Transitions.applyExternal(store, clock, (i, s) -> {}, item, new Trigger.Approved());
    assertThat(store.readWorkItem("w1").attempt()).isZero();
    assertThat(store.readEvents("w1").getFirst().data()).containsEntry("run", 0);
  }
}
