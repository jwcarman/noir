package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.FakeBranchPublisher;
import org.jwcarman.noir.factory.Daemon;
import org.jwcarman.noir.factory.GitHubDelivery;
import org.jwcarman.noir.factory.Reconciler;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.lifecycle.BlockReason;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.store.InstanceLock;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class CancelResumeCommandTest {
  static final String REPO = "jwcarman/demo";
  static final String A = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A";

  @TempDir Path tmp;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();
  final FakeGitHub gh = new FakeGitHub();
  Store store;
  int issue;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(tmp));
    issue = gh.createIssue(REPO, "t", "b", List.of("noir:blocked", "bug"));
  }

  int run(String... args) {
    var ops =
        new NoirCli.Operations(
            home -> (id, dir) -> null,
            home ->
                (once, listener) -> {
                  throw new AssertionError("run must not be invoked");
                },
            () -> gh,
            Set::of);
    return NoirCli.execute(
        new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), ops, args);
  }

  WorkItem item(State state) {
    var item = WorkItem.sealed(A, REPO, issue).withState(state).withAttempt(2);
    store.writeWorkItem(item);
    return item;
  }

  WorkItem blocked(BlockReason reason, State phase) {
    var item =
        WorkItem.sealed(A, REPO, issue)
            .withState(State.BLOCKED)
            .withAttempt(2)
            .withBlock(reason, phase);
    store.writeWorkItem(item);
    return item;
  }

  @Test
  void cancelMovesItemToCancelledAndRemovesTheLabel() {
    item(State.BUILDING);

    int code = run("cancel", A);

    assertThat(code).isZero();
    assertThat(out.toString()).isEqualTo(A + " BUILDING → CANCELLED\n");
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.CANCELLED);
    assertThat(gh.issue(issue).labels()).containsExactly("bug");
    var event = store.readEvents(A).getLast();
    assertThat(event.type()).isEqualTo("transition");
    assertThat(event.data()).containsEntry("trigger", "CancelRequested");
  }

  @Test
  void cancellingATerminalItemIsRefused() {
    item(State.PR_OPEN);

    int code = run("cancel", A);

    assertThat(code).isEqualTo(1);
    assertThat(err.toString()).isEqualTo("Trigger CancelRequested is not valid in state PR_OPEN\n");
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.PR_OPEN);
  }

  @Test
  void cancelIsRefusedWhileTheDaemonRuns() {
    item(State.BUILDING);
    try (var held = InstanceLock.tryAcquire(new NoirHome(tmp).lock()).orElseThrow()) {
      int code = run("cancel", A);
      assertThat(code).isEqualTo(2);
    }
    assertThat(err.toString())
        .isEqualTo("noir run is active; stop it first, or close the issue to cancel\n");
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.BUILDING);
  }

  @Test
  void resumeRestartsTheBlockedPhase() {
    blocked(BlockReason.INFRASTRUCTURE, State.MEASURING);

    int code = run("resume", A);

    assertThat(code).isZero();
    assertThat(out.toString()).isEqualTo(A + " BLOCKED → MEASURING\n");
    var item = store.readWorkItem(A);
    assertThat(item.state()).isEqualTo(State.MEASURING);
    assertThat(item.blockReason()).isNull();
    assertThat(gh.issue(issue).labels()).containsExactlyInAnyOrder("bug", "noir:building");
    assertThat(store.readEvents(A).getLast().data()).containsEntry("trigger", "ResumeRequested");
  }

  @Test
  void resumeWorksWhileTheDaemonRuns() {
    blocked(BlockReason.SECRET_FOUND, State.GRADING);
    try (var held = InstanceLock.tryAcquire(new NoirHome(tmp).lock()).orElseThrow()) {
      assertThat(run("resume", A)).isZero();
    }
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.GRADING);
  }

  @Test
  void holdoutDefectCannotBeResumed() {
    var before = blocked(BlockReason.HOLDOUT_DEFECT, State.GRADING);

    int code = run("resume", A);

    assertThat(code).isEqualTo(1);
    assertThat(err.toString()).isEqualTo("Trigger ResumeRequested is not valid in state BLOCKED\n");
    assertThat(store.readWorkItem(A)).isEqualTo(before);
    assertThat(gh.issue(issue).labels()).containsExactlyInAnyOrder("noir:blocked", "bug");
    assertThat(store.readEvents(A)).isEmpty();
  }

  @Test
  void unknownItemIsRefused() {
    assertThat(run("cancel", A)).isEqualTo(1);
    assertThat(run("resume", "not-an-id")).isEqualTo(1);
    assertThat(err.toString()).isEqualTo("No work item " + A + "\nNo work item not-an-id\n");
  }

  @Test
  void gitHubFailureIsOneLineAndChangesNothing() {
    var before = item(State.GRADING);
    gh.failNextSetNoirLabel();

    int code = run("cancel", A);

    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("simulated setNoirLabel failure\n");
    assertThat(store.readWorkItem(A)).isEqualTo(before);
    assertThat(store.readEvents(A)).isEmpty();
  }

  @Test
  void resumeIsRefusedWhenTheIssueWasClosed() {
    var before = blocked(BlockReason.INFRASTRUCTURE, State.MEASURING);
    gh.closeIssue(issue);

    int code = run("resume", A);

    assertThat(code).isEqualTo(1);
    assertThat(err.toString())
        .isEqualTo("Issue " + REPO + "#" + issue + " is closed; not resuming " + A + "\n");
    assertThat(store.readWorkItem(A)).isEqualTo(before);
    assertThat(gh.issue(issue).labels()).containsExactlyInAnyOrder("noir:blocked", "bug");
    assertThat(store.readEvents(A)).isEmpty();
  }

  @Test
  void anItemTheDaemonBlockedCanBeResumed() {
    store.writeWorkItem(WorkItem.sealed(A, REPO, issue).withState(State.BUILDING).withAttempt(1));
    var clock = java.time.Clock.systemUTC();
    var daemon =
        new Daemon(
            store,
            gh,
            new org.jwcarman.noir.docker.FakeContainerPort(spec -> null),
            "noir.run=test",
            id -> {
              throw new IllegalStateException("boom");
            },
            new Reconciler(
                store, gh, new GitHubDelivery(gh, new FakeBranchPublisher()), clock, Set.of()),
            new org.jwcarman.noir.factory.Dispositions(
                store, gh, new org.jwcarman.noir.factory.GitBundles(null, tmp), clock),
            NoirConfig.defaults(),
            d -> {},
            clock,
            new java.util.SplittableRandom(1),
            Set.of(),
            new Daemon.Listener() {
              @Override
              public void transition(org.jwcarman.noir.factory.StateChange change) {}

              @Override
              public void error(String itemId, String message) {}

              @Override
              public void warning(String message) {}

              @Override
              public void created(String itemId, String repo, int issue) {}
            });
    daemon.runOnce();
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.BLOCKED);

    int code = run("resume", A);

    assertThat(code).isZero();
    assertThat(out.toString()).isEqualTo(A + " BLOCKED → BUILDING\n");
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.BUILDING);
    assertThat(gh.issue(issue).labels()).containsExactlyInAnyOrder("bug", "noir:building");
  }
}
