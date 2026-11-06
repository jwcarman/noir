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
import java.util.SplittableRandom;
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
import org.jwcarman.noir.store.Event;
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
  final FakeGitHub gh = new FakeGitHub().withNoirLabels(REPO, OTHER_REPO);
  final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
  final FakeDriver driver = new FakeDriver();
  final List<Duration> sleeps = new ArrayList<>();
  final List<String> reported = new ArrayList<>();
  final List<String> working = new ArrayList<>();
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

        @Override
        public void created(String itemId, String repo, int issue) {
          reported.add("created " + itemId + " " + repo + "#" + issue);
        }

        @Override
        public void started(List<String> repos, Duration pollInterval) {
          reported.add("started " + repos + " every " + pollInterval);
        }

        @Override
        public void working(String itemId, String repo, Integer issue, State state) {
          // Kept apart from reported; the suffix is how many lines were reported before it.
          working.add(itemId + " " + repo + "#" + issue + " " + state + " @" + reported.size());
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
            defaults.examiner(),
            defaults.maven());
    var reconciler = new Reconciler(store, gh, reconcileDelivery, clock, Set.of(SECRET));
    return new Daemon(
        store,
        gh,
        docker,
        "noir.run=h1",
        driver,
        reconciler,
        new Dispositions(store, gh, new NoTipBundles(), clock),
        config,
        sleeper,
        clock,
        new SplittableRandom(7),
        Set.of(SECRET),
        listener);
  }

  static final class NoTipBundles implements Bundles {
    @Override
    public List<String> commitsSince(Path bundle, String baseSha, String branch) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String diffSince(
        Path bundle, String baseSha, String branch, java.util.List<String> paths) {
      return "";
    }

    @Override
    public String patchSince(Path bundle, String baseSha, String branch) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<org.jwcarman.noir.deliver.FileChange> fileChanges(
        Path bundle, String baseSha, String branch) {
      return java.util.List.of();
    }

    @Override
    public String tip(Path bundle, String branch) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void passRecordsDispositions() {
    int issue = issue(REPO, "noir:pr-open");
    store.writeWorkItem(WorkItem.sealed(A, REPO, issue).withState(State.PR_OPEN).withAttempt(1));
    int pr = gh.createPullRequest(REPO, "main", "noir/x", "t", "b", false);
    store.appendEvent(
        Event.of(
            clock,
            A,
            "delivered",
            1,
            Map.of("pullRequest", pr, "draft", false, "deliveredAttempt", 1)));
    gh.merge(pr, "abc123");

    daemon().runOnce();

    assertThat(store.readEvents(A))
        .filteredOn(e -> e.type().equals("pr_disposition"))
        .singleElement()
        .satisfies(e -> assertThat(e.data()).containsEntry("disposition", "modified"));
  }

  void deliveredPullRequest(String id) {
    int issue = issue(REPO, "noir:pr-open");
    store.writeWorkItem(WorkItem.sealed(id, REPO, issue).withState(State.PR_OPEN).withAttempt(1));
    int pr = gh.createPullRequest(REPO, "main", "noir/x", "t", "b", false);
    store.appendEvent(
        Event.of(
            clock,
            id,
            "delivered",
            1,
            Map.of("pullRequest", pr, "draft", false, "deliveredAttempt", 1)));
  }

  @Test
  void refreshWarningsAreRedacted() {
    deliveredPullRequest(A);
    gh.failPullRequests("bad token " + SECRET);

    daemon().runOnce();

    assertThat(reported)
        .filteredOn(r -> r.startsWith("warning " + A))
        .singleElement()
        .asString()
        .contains("[redacted]")
        .doesNotContain(SECRET);
  }

  @Test
  void repeatedRefreshWarningIsReportedOnce() {
    deliveredPullRequest(A);
    gh.failPullRequests("gh is down");
    var d = daemon();

    d.runOnce();
    d.runOnce();
    d.runOnce();

    assertThat(reported).filteredOn(r -> r.startsWith("warning " + A)).hasSize(1);
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

          @Override
          public void removeReady(WorkItem item) {
            throw new UnsupportedOperationException();
          }
        };
  }

  int issue(String repo, String... labels) {
    return gh.createIssue(repo, "t", "b", List.of(labels));
  }

  void item(String id, String repo, int issue, State state) {
    store.writeWorkItem(WorkItem.sealed(id, repo, issue).withState(state));
  }

  @Test
  void picksSealedItemsOnlyInConfiguredRepos() {
    freezeLabels();
    item(A, REPO, issue(REPO, "noir:ready"), State.SEALED);
    item(B, REPO, issue(REPO, "noir:building"), State.SEALED);
    item(C, OTHER_REPO, issue(OTHER_REPO, "noir:ready"), State.SEALED);

    int advanced = daemon().runOnce();

    assertThat(advanced).isEqualTo(2);
    assertThat(driver.driven).containsExactly(A, B);
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.PR_OPEN);
    assertThat(store.readWorkItem(B).state()).isEqualTo(State.PR_OPEN);
    assertThat(store.readWorkItem(C).state()).isEqualTo(State.SEALED);
  }

  /**
   * An item picked up mid-phase (resumed, or after a restart) is announced once before it is
   * driven, so the operator sees the server working; its transitions follow as usual.
   */
  @Test
  void announcesEachItemItDrivesOnce() {
    freezeLabels();
    int n = issue(REPO, "noir:building");
    item(A, REPO, n, State.MEASURING);

    daemon().runOnce();

    assertThat(working).containsExactly(A + " " + REPO + "#" + n + " MEASURING @0");
    assertThat(reported).first().isEqualTo(A + " MEASURING -> GRADING");
  }

  // ---- labels ------------------------------------------------------------------------------

  @Test
  void ensuresLabelsOncePerRepoOnFirstPass() {
    // Checked inside the drive: labels exist before any item is reconciled or driven.
    item(A, REPO, issue(REPO), State.GRADING);
    driver.failures.put(A, id -> assertThat(gh.ensuredRepos()).containsExactly(REPO));
    var daemon = daemon();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(gh.ensuredRepos()).containsExactly(REPO);
    assertThat(driver.driven).containsExactly(A);
  }

  @Test
  void ensureLabelsFailureSkipsRepoAndRetriesNextPass() {
    int n = issue(REPO, "noir:ready");
    gh.failEnsureLabels(1, new IllegalStateException("gh down for token " + SECRET));
    var daemon = daemon();

    daemon.runOnce();

    assertThat(store.itemIds()).isEmpty();
    assertThat(gh.ensuredRepos()).isEmpty();
    assertThat(reported)
        .singleElement()
        .asString()
        .startsWith("error null: " + REPO + ": ")
        .contains("gh down")
        .doesNotContain(SECRET);

    daemon.runOnce();
    daemon.runOnce();

    assertThat(gh.ensuredRepos()).containsExactly(REPO);
    assertThat(store.findItems(REPO, n)).hasSize(1);
  }

  // ---- approval discovery ------------------------------------------------------------------

  @Test
  void readyIssueCreatesItemAndSeals() {
    int n = issue(REPO, "noir:ready");

    int advanced = daemon().runOnce();

    assertThat(advanced).isEqualTo(1);
    var ids = store.itemIds();
    assertThat(ids).singleElement().matches(org.jwcarman.noir.spec.ItemId::isValid);
    var id = ids.getFirst();
    assertThat(store.readEvents(id).getFirst())
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo("item_created");
          assertThat(e.data()).containsEntry("issue", n);
        });
    assertThat(driver.driven).containsExactly(id);
    assertThat(driver.states).startsWith(State.SEALING);
    assertThat(reported)
        .startsWith("created " + id + " " + REPO + "#" + n, id + " SEALING -> SEALED");
    var item = store.readWorkItem(id);
    assertThat(item.repo()).isEqualTo(REPO);
    assertThat(item.issueNumber()).isEqualTo(n);
    assertThat(item.state()).isEqualTo(State.PR_OPEN);
  }

  @Test
  void readyIssueInUnconfiguredRepoIgnored() {
    issue(OTHER_REPO, "noir:ready");

    assertThat(daemon().runOnce()).isZero();

    assertThat(store.itemIds()).isEmpty();
    assertThat(reported).isEmpty();
  }

  @Test
  void draftItemReapprovedMovesToSealing() {
    int n = issue(REPO, "noir:ready", "noir:draft");
    item(A, REPO, n, State.DRAFT);

    daemon().runOnce();

    assertThat(store.itemIds()).containsExactly(A);
    assertThat(store.readEvents(A).getFirst().data())
        .containsEntry("from", "DRAFT")
        .containsEntry("to", "SEALING")
        .containsEntry("trigger", "Approved");
    assertThat(reported).startsWith(A + " DRAFT -> SEALING", A + " SEALING -> SEALED");
    assertThat(driver.driven).containsExactly(A);
  }

  @Test
  void terminalItemIsNeverRecreated() {
    int n = issue(REPO, "noir:ready");
    item(A, REPO, n, State.FAILED);
    var daemon = daemon();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(store.itemIds()).containsExactly(A);
    assertThat(driver.driven).isEmpty();
    assertThat(reported)
        .containsExactly(
            "warning "
                + REPO
                + "#"
                + n
                + " has noir:ready but its work item "
                + A
                + " is FAILED; open a new issue");
  }

  @Test
  void prOpenItemWithReadyIssueIsLeftAlone() {
    int n = issue(REPO, "noir:ready", "noir:pr-open");
    item(A, REPO, n, State.PR_OPEN);
    var daemon = daemon();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(store.itemIds()).containsExactly(A);
    assertThat(driver.driven).isEmpty();
    assertThat(reported).isEmpty();
  }

  @Test
  void unreadableItemStopsItemCreation() throws java.io.IOException {
    var broken = store.itemDir(B);
    java.nio.file.Files.createDirectories(broken);
    java.nio.file.Files.writeString(broken.resolve("workitem.json"), "{not json");
    int ready = issue(REPO, "noir:ready");
    int draft = issue(REPO, "noir:ready", "noir:draft");
    item(A, REPO, draft, State.DRAFT);
    var daemon = daemon();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(store.itemIds()).containsExactly(A, B);
    assertThat(store.findItem(REPO, ready)).isEmpty();
    assertThat(reported)
        .filteredOn(r -> r.startsWith("warning"))
        .containsExactly(
            "warning not creating work items: unreadable work item(s) ["
                + B
                + "]; fix or remove them");
    assertThat(reported).contains(A + " DRAFT -> SEALING");
  }

  @Test
  void crashAfterItemCreationResumesSealing() {
    int n = issue(REPO, "noir:ready");
    store.writeWorkItem(WorkItem.approved(A, REPO, n));

    daemon().runOnce();

    assertThat(store.itemIds()).containsExactly(A);
    assertThat(driver.driven).containsExactly(A);
    assertThat(driver.states).startsWith(State.SEALING);
    assertThat(reported).startsWith(A + " SEALING -> SEALED");
  }

  @Test
  void draftIsNeverDriven() {
    item(A, REPO, issue(REPO, "noir:draft"), State.DRAFT);

    assertThat(daemon().runOnce()).isZero();

    assertThat(driver.driven).isEmpty();
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.DRAFT);
    assertThat(reported).isEmpty();
  }

  @Test
  void sealedItemBuildsWithoutReadyLabel() {
    item(A, REPO, issue(REPO), State.SEALED);

    assertThat(daemon().runOnce()).isEqualTo(1);

    assertThat(driver.driven).containsExactly(A);
    assertThat(store.readWorkItem(A).state()).isEqualTo(State.PR_OPEN);
  }

  @Test
  void rejectedSealStopsAtDraft() {
    int n = issue(REPO, "noir:ready");
    store.writeWorkItem(WorkItem.approved(A, REPO, n));
    driver.rejectSealing = true;

    daemon().runOnce();

    assertThat(store.readWorkItem(A).state()).isEqualTo(State.DRAFT);
    assertThat(reported).containsExactly(A + " SEALING -> DRAFT");
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
        .filteredOn(r -> r.contains(" skipped: "))
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
    assertThat(gh.issue(failing).labels()).containsExactlyInAnyOrder("noir:ready", "noir:building");
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
  void sealingItemThatFailsIsBlockedWithOneNotice() {
    int n = issue(REPO, "noir:ready", "noir:sealing");
    store.writeWorkItem(WorkItem.approved(A, REPO, n));
    driver.failures.put(
        A,
        id -> {
          throw new IllegalStateException("HiddenTest.secretCase failed with " + SECRET);
        });
    var daemon = daemon();

    daemon.runOnce();
    daemon.runOnce();

    assertThat(store.readWorkItem(A).state()).isEqualTo(State.BLOCKED);
    assertThat(gh.comments(n))
        .containsExactly(
            "noir hit an infrastructure problem while sealing this issue. Nothing about the spec"
                + " is wrong. The operator can retry with `noir resume "
                + A
                + "` on the noir server.");
    assertThat(store.readEvents(A)).filteredOn(e -> e.type().equals("blocked_notice")).hasSize(1);
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
    assertThat(reported).first().asString().startsWith("started [");
    assertThat(reported).filteredOn(r -> r.startsWith("started")).hasSize(1);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  /** Moves items one state per advance along the happy path. */
  final class FakeDriver implements ItemDriver {
    final List<String> driven = new ArrayList<>();
    final Map<String, Consumer<String>> failures = new HashMap<>();
    final List<State> states = new ArrayList<>();
    Consumer<String> afterAdvance = id -> {};
    boolean rejectSealing;

    @Override
    public WorkItem advance(String itemId) {
      if (driven.isEmpty() || !driven.getLast().equals(itemId)) {
        driven.add(itemId);
      }
      failures.getOrDefault(itemId, id -> {}).accept(itemId);
      var item = store.readWorkItem(itemId);
      states.add(item.state());
      var next =
          switch (item.state()) {
            case SEALING -> rejectSealing ? State.DRAFT : State.SEALED;
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
