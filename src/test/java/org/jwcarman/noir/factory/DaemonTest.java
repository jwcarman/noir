package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.FakeBranchPublisher;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.FakeContainerPort;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class DaemonTest {
  static final String REPO = "jwcarman/demo";
  static final String OTHER_REPO = "jwcarman/other";
  static final String SECRET = "sk-ant-secret-value";
  static final String A = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A";
  static final String B = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1B";
  static final String C = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1C";

  @TempDir Path home;
  Store store;
  final FakeGitHub gh = new FakeGitHub();
  final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
  final FakeDriver driver = new FakeDriver();
  final List<Duration> sleeps = new ArrayList<>();
  final List<String> reported = new ArrayList<>();
  Retry.Sleeper sleeper = sleeps::add;
  final FakeContainerPort docker = new FakeContainerPort(spec -> null);
  Delivery reconcileDelivery;

  final Daemon.Listener listener =
      new Daemon.Listener() {
        @Override
        public void transition(StateChange change) {
          reported.add(change.itemId() + " " + change.from() + " -> " + change.to());
        }

        @Override
        public void error(String itemId, String message) {
          reported.add("error " + itemId + ": " + message);
        }

        @Override
        public void warning(String message) {
          reported.add("warning " + message);
        }
      };

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(home));
    reconcileDelivery = new GitHubDelivery(gh, new FakeBranchPublisher());
  }

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  Daemon daemon() {
    var defaults = NoirConfig.defaults();
    var config =
        new NoirConfig(
            List.of(REPO),
            defaults.qualityBar(),
            defaults.sealDiscrimination(),
            defaults.budgets(),
            defaults.reworkRetries(),
            Duration.ofSeconds(42),
            defaults.infraRetries(),
            defaults.limits(),
            defaults.images(),
            defaults.timeouts(),
            defaults.examiner());
    var reconciler = new Reconciler(store, gh, reconcileDelivery, clock);
    return new Daemon(
        store,
        gh,
        docker,
        "noir.run=h1",
        driver,
        reconciler,
        config,
        sleeper,
        clock,
        Set.of(SECRET),
        listener);
  }

  /** Reconciliation that never touches labels, so the daemon's own label filter is observable. */
  void freezeLabels() {
    reconcileDelivery =
        new Delivery() {
          @Override
          public int openPullRequest(
              WorkItem item, Spec spec, AttemptPaths attempt, String body, boolean draft) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void comment(WorkItem item, String body) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void label(WorkItem item, State state) {}
        };
  }

  int issue(String repo, String... labels) {
    return gh.createIssue(repo, "t", "b", List.of(labels));
  }

  void item(String id, String repo, int issue, State state) {
    store.writeWorkItem(WorkItem.sealed(id, repo, issue).withState(state));
  }

  @Test
  void picksOnlySealedItemsWhoseIssueIsReady() {
    freezeLabels();
    item(A, REPO, issue(REPO, "noir:ready"), State.SEALED);
    item(B, REPO, issue(REPO, "noir:building"), State.SEALED);
    item(C, OTHER_REPO, issue(OTHER_REPO, "noir:ready"), State.SEALED);

    int advanced = daemon().runOnce();

    assertThat(advanced).isEqualTo(1);
    assertThat(driver.driven).containsExactly(A);
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.PR_OPEN);
    assertThat(store.readWorkItem(B).state()).isEqualTo(State.SEALED);
    assertThat(store.readWorkItem(C).state()).isEqualTo(State.SEALED);
  }

  @Test
  void warnsOnceForASealedItemWhoseRepoIsNotConfigured() {
    freezeLabels();
    item(A, REPO, issue(REPO, "noir:ready"), State.SEALED);
    item(C, OTHER_REPO, issue(OTHER_REPO, "noir:ready"), State.SEALED);
    item(B, OTHER_REPO, issue(OTHER_REPO), State.FAILED);
    var daemon = daemon();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(reported)
        .filteredOn(r -> r.startsWith("warning"))
        .containsExactly(
            "warning "
                + C
                + " skipped: repo "
                + OTHER_REPO
                + " is not in ~/.noir/config.yaml repos");
    assertThat(store.readWorkItem(C).state()).isEqualTo(State.SEALED);
  }

  @Test
  void removesLeftoverNoirContainersOnceBeforeTheFirstPass() {
    item(A, REPO, issue(REPO), State.GRADING);
    // Checked inside the drive: an AssertionError is not caught as an item failure.
    driver.failures.put(A, id -> assertThat(docker.removedLabels()).containsExactly("noir.run=h1"));
    var daemon = daemon();
    assertThat(docker.removedLabels()).isEmpty();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(docker.removedLabels()).containsExactly("noir.run=h1");
  }

  @Test
  void failingSweepStopsTheRunBeforeAnyWork() {
    item(A, REPO, issue(REPO), State.GRADING);
    docker.failRemoveLabelled(new DockerException("Docker is not running; start Docker Desktop"));

    assertThatThrownBy(() -> daemon().runOnce())
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("Docker is not running");
    assertThat(driver.driven).isEmpty();
  }

  @Test
  void picksReworkAndActiveItemsWithoutTheReadyLabel() {
    freezeLabels();
    item(A, REPO, issue(REPO), State.REWORK);
    item(B, REPO, issue(REPO), State.MEASURING);
    item(C, REPO, issue(REPO), State.BLOCKED);

    int advanced = daemon().runOnce();

    assertThat(advanced).isEqualTo(2);
    assertThat(driver.driven).containsExactly(A, B);
    assertThat(store.readWorkItem(C).state()).isEqualTo(State.BLOCKED);
  }

  @Test
  void drivesOldestIdFirst() {
    item(C, REPO, issue(REPO, "noir:ready"), State.SEALED);
    item(A, REPO, issue(REPO, "noir:ready"), State.SEALED);
    item(B, REPO, issue(REPO), State.REWORK);

    daemon().runOnce();

    assertThat(driver.driven).containsExactly(A, B, C);
  }

  @Test
  void secondPassWithNothingNewAdvancesNothing() {
    item(A, REPO, issue(REPO, "noir:ready"), State.SEALED);
    var daemon = daemon();

    assertThat(daemon.runOnce()).isEqualTo(1);
    driver.driven.clear();

    assertThat(daemon.runOnce()).isZero();
    assertThat(driver.driven).isEmpty();
  }

  @Test
  void reportsEachTransition() {
    item(A, REPO, issue(REPO), State.GRADING);

    daemon().runOnce();

    assertThat(reported).containsExactly(A + " GRADING -> PR_OPEN");
  }

  @Test
  void reconciliationRunsFirstAndCancelsClosedIssues() {
    int n = issue(REPO, "noir:ready");
    item(A, REPO, n, State.SEALED);
    item(B, REPO, issue(REPO), State.BUILDING);
    gh.closeIssue(n);

    daemon().runOnce();

    assertThat(store.readWorkItem(A).state()).isEqualTo(State.CANCELLED);
    assertThat(driver.driven).containsExactly(B);
    assertThat(reported).startsWith(A + " SEALED -> CANCELLED");
  }

  @Test
  void oneFailingItemDoesNotStopTheOthers() {
    int failing = issue(REPO, "noir:ready");
    item(A, REPO, failing, State.SEALED);
    item(B, REPO, issue(REPO, "noir:ready"), State.SEALED);
    driver.failures.put(
        A,
        id -> {
          throw new IllegalStateException("docker failed\nwith token " + SECRET + " in output");
        });

    int advanced = daemon().runOnce();

    assertThat(advanced).isEqualTo(1);
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.SEALED);
    assertThat(store.readWorkItem(B).state()).isEqualTo(State.PR_OPEN);
    var event = store.readEvents(A).getLast();
    assertThat(event.type()).isEqualTo("daemon_error");
    var error = (String) event.data().get("error");
    assertThat(error).doesNotContain(SECRET).doesNotContain("\n").contains("docker failed");
    // A SEALED item stays SEALED and keeps its state label (no flip to noir:blocked).
    assertThat(gh.issue(failing).labels()).containsExactly("noir:ready");
    assertThat(reported).anySatisfy(r -> assertThat(r).startsWith("error " + A + ": "));
    assertThat(String.join("\n", reported)).doesNotContain(SECRET);
  }

  @Test
  void activeItemThatFailsIsBlockedAndNotRetried() {
    int n = issue(REPO, "noir:building");
    store.writeWorkItem(WorkItem.sealed(A, REPO, n).withState(State.BUILDING).withAttempt(2));
    driver.failures.put(
        A,
        id -> {
          throw new java.io.UncheckedIOException(
              "Cannot delete planted dir", new java.io.IOException("denied"));
        });
    var daemon = daemon();

    daemon.runOnce();

    var item = store.readWorkItem(A);
    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason())
        .isEqualTo(org.jwcarman.noir.lifecycle.BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.BUILDING);
    assertThat(item.attempt()).isEqualTo(2);
    var types = store.readEvents(A).stream().map(e -> e.type()).toList();
    assertThat(types).containsSubsequence("daemon_error", "transition");
    assertThat(store.readEvents(A).getLast().data())
        .containsEntry("from", "BUILDING")
        .containsEntry("to", "BLOCKED")
        .containsEntry("trigger", "InfraBlocked");
    assertThat(gh.issue(n).labels()).containsExactly("noir:blocked");
    assertThat(reported).contains(A + " BUILDING -> BLOCKED");

    driver.driven.clear();
    daemon.runOnce();

    assertThat(driver.driven).isEmpty();
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.BLOCKED);
    assertThat(gh.issue(n).labels()).containsExactly("noir:blocked");
  }

  @Test
  void reconcileFailureForOneItemDoesNotStopThePass() {
    item(A, REPO, 99, State.BUILDING);
    item(B, REPO, issue(REPO), State.BUILDING);

    daemon().runOnce();

    assertThat(driver.driven).contains(B);
    assertThat(store.readEvents(A)).anySatisfy(e -> assertThat(e.type()).isEqualTo("daemon_error"));
    assertThat(reported).anySatisfy(r -> assertThat(r).startsWith("error " + A + ": "));
  }

  @Test
  void interruptStopsBetweenItemsAndKeepsTheFlag() {
    item(A, REPO, issue(REPO), State.GRADING);
    item(B, REPO, issue(REPO), State.GRADING);
    driver.afterAdvance = id -> Thread.currentThread().interrupt();

    int advanced = daemon().runOnce();

    assertThat(advanced).isEqualTo(1);
    assertThat(driver.driven).containsExactly(A);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void runForeverPollsUntilInterrupted() {
    sleeper =
        d -> {
          sleeps.add(d);
          if (sleeps.size() == 2) {
            Thread.currentThread().interrupt();
          }
        };
    item(A, REPO, issue(REPO, "noir:ready"), State.SEALED);

    daemon().runForever();

    assertThat(sleeps).containsExactly(Duration.ofSeconds(42), Duration.ofSeconds(42));
    assertThat(driver.driven).containsExactly(A);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  /** Moves items one state per advance along the happy path. */
  final class FakeDriver implements ItemDriver {
    final List<String> driven = new ArrayList<>();
    final Map<String, Consumer<String>> failures = new HashMap<>();
    Consumer<String> afterAdvance = id -> {};

    @Override
    public WorkItem advance(String itemId) {
      if (driven.isEmpty() || !driven.getLast().equals(itemId)) {
        driven.add(itemId);
      }
      failures.getOrDefault(itemId, id -> {}).accept(itemId);
      var item = store.readWorkItem(itemId);
      var next =
          switch (item.state()) {
            case SEALED, REWORK -> State.BUILDING;
            case BUILDING -> State.MEASURING;
            case MEASURING -> State.GRADING;
            case GRADING -> State.PR_OPEN;
            default -> throw new IllegalStateException("cannot advance " + item.state());
          };
      var updated = item.withState(next);
      store.writeWorkItem(updated);
      afterAdvance.accept(itemId);
      return updated;
    }
  }
}
