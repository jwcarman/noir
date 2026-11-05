package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.deliver.FakeBranchPublisher;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.lifecycle.BlockReason;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class ReconcilerTest {
  static final String REPO = "jwcarman/demo";
  static final String A = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A";
  static final String B = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1B";

  @TempDir Path home;
  Store store;
  final FakeGitHub gh = new FakeGitHub();
  final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
  Reconciler reconciler;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(home));
    reconciler =
        new Reconciler(store, gh, new GitHubDelivery(gh, new FakeBranchPublisher()), clock);
  }

  int issue(String... labels) {
    return gh.createIssue(REPO, "t", "b", List.of(labels));
  }

  WorkItem item(String id, int issue, State state) {
    var item = WorkItem.sealed(id, REPO, issue).withState(state).withAttempt(2);
    store.writeWorkItem(item);
    return item;
  }

  @Test
  void closedIssueCancelsItem() {
    int n = issue("noir:building");
    item(A, n, State.MEASURING);
    gh.closeIssue(n);

    var changes = reconciler.reconcile();

    assertThat(store.readWorkItem(A).state()).isEqualTo(State.CANCELLED);
    assertThat(changes).containsExactly(new StateChange(A, State.MEASURING, State.CANCELLED));
    assertThat(gh.issue(n).labels()).isEmpty();
    var event = store.readEvents(A).getLast();
    assertThat(event.type()).isEqualTo("transition");
    assertThat(event.data())
        .containsEntry("from", "MEASURING")
        .containsEntry("to", "CANCELLED")
        .containsEntry("trigger", "IssueClosed");
  }

  @Test
  void closedIssueCancelsBlockedItem() {
    int n = issue("noir:blocked");
    var blocked =
        WorkItem.sealed(A, REPO, n)
            .withState(State.BLOCKED)
            .withBlock(BlockReason.HOLDOUT_DEFECT, State.GRADING);
    store.writeWorkItem(blocked);
    gh.closeIssue(n);

    reconciler.reconcile();

    assertThat(store.readWorkItem(A).state()).isEqualTo(State.CANCELLED);
  }

  @Test
  void openIssueGetsItsStateLabelReapplied() {
    int sealed = issue("noir:building", "bug");
    int grading = issue("noir:ready");
    item(A, sealed, State.SEALED);
    item(B, grading, State.GRADING);

    var changes = reconciler.reconcile();

    assertThat(changes).isEmpty();
    assertThat(gh.issue(sealed).labels()).containsExactlyInAnyOrder("bug", "noir:building");
    assertThat(gh.issue(grading).labels()).containsExactlyInAnyOrder("noir:ready", "noir:building");
  }

  @Test
  void activeStatesArePreserved() {
    for (var state : List.of(State.BUILDING, State.MEASURING, State.GRADING)) {
      int n = issue();
      var id = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T" + (char) ('C' + state.ordinal());
      var before = item(id, n, state);

      reconciler.reconcile();

      assertThat(store.readWorkItem(id)).isEqualTo(before);
      assertThat(store.readEvents(id)).isEmpty();
    }
  }

  @Test
  void terminalItemsAreSkipped() {
    int n = issue("custom");
    item(A, n, State.PR_OPEN);
    item(B, n, State.FAILED);
    gh.closeIssue(n);

    var changes = reconciler.reconcile();

    assertThat(changes).isEmpty();
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.PR_OPEN);
    assertThat(store.readWorkItem(B).state()).isEqualTo(State.FAILED);
    assertThat(gh.issue(n).labels()).containsExactly("custom");
  }
}
