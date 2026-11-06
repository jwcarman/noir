package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.factory.Bundles;
import org.jwcarman.noir.factory.Dispositions;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.InstanceLock;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class StatusCommandTest {
  static final String ID = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W";
  static final String SECRET = "sk-ant-secret-value";
  @TempDir Path tmp;
  Store store;
  final FakeGitHub gh = new FakeGitHub().withNoirLabels("jwcarman/demo");
  final StringWriter stderr = new StringWriter();
  final List<NoirHome> refreshed = new ArrayList<>();

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(tmp));
  }

  @Test
  void emptyStore() {
    assertThat(run("status")).isEqualTo("No work items.\n");
  }

  @Test
  void listsItems() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var out = run("status");
    assertThat(out.lines().findFirst().orElseThrow())
        .contains("ID")
        .contains("REPO")
        .contains("ISSUE")
        .contains("STATE")
        .contains("ATTEMPT");
    assertThat(out).contains(ID).contains("jwcarman/demo").contains("#7").contains("SEALED");
  }

  @Test
  void json() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/items/0/id").asText()).isEqualTo(ID);
    assertThat(node.at("/items/0/repo").asText()).isEqualTo("jwcarman/demo");
    assertThat(node.at("/items/0/state").asText()).isEqualTo("SEALED");
    assertThat(node.at("/items/0/issue").asInt()).isEqualTo(7);
    assertThat(node.at("/items/0/attempt").asInt()).isZero();
    assertThat(node.at("/items/0/reworkCount").asInt()).isZero();
    assertThat(node.at("/items/0/blockReason").isNull()).isTrue();
  }

  @Test
  void jsonEmpty() throws Exception {
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/items").isArray()).isTrue();
    assertThat(node.at("/items").size()).isZero();
  }

  @Test
  void unreadableItemDoesNotHideOthers() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var broken = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1X";
    java.nio.file.Files.createDirectories(store.itemDir(broken));
    assertThat(run("status")).contains(ID).contains(broken).contains("UNREADABLE");
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/items/1/id").asText()).isEqualTo(broken);
    assertThat(node.at("/items/1/state").asText()).isEqualTo("UNREADABLE");
    assertThat(node.at("/items/1/error").asText()).contains("workitem.json");
  }

  @Test
  void homeOptionOverridesDefault() {
    var other = tmp.resolve("other");
    new Store(new NoirHome(other)).writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    assertThat(run("--home", other.toString(), "status")).contains(ID);
  }

  @Test
  void statusWithoutRefreshMakesNoGitHubCalls() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    run("status");
    assertThat(refreshed).isEmpty();
  }

  @Test
  void refreshRecordsDispositionsBeforePrinting() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7).withState(State.PR_OPEN));
    int pr = gh.createPullRequest("jwcarman/demo", "main", "noir/x", "t", "b", false);
    store.appendEvent(
        Event.of(
            Clock.systemUTC(),
            ID,
            "delivered",
            1,
            Map.of("pullRequest", pr, "draft", false, "deliveredAttempt", 1)));
    gh.merge(pr, "abc");

    run("status", "--refresh");

    assertThat(store.readEvents(ID))
        .filteredOn(e -> e.type().equals("pr_disposition"))
        .singleElement()
        .satisfies(e -> assertThat(e.data()).containsEntry("disposition", "modified"));
  }

  @Test
  void refreshWarningsGoToStderr() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7).withState(State.PR_OPEN));
    store.appendEvent(
        Event.of(
            Clock.systemUTC(),
            ID,
            "delivered",
            1,
            Map.of("pullRequest", 42, "draft", false, "deliveredAttempt", 1)));

    var stdout = run("status", "--refresh");

    assertThat(stdout).contains(ID);
    assertThat(stderr.toString().lines().toList()).singleElement().asString().startsWith(ID);
  }

  @Test
  void refreshWarningsAreRedacted() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7).withState(State.PR_OPEN));
    store.appendEvent(
        Event.of(
            Clock.systemUTC(),
            ID,
            "delivered",
            1,
            Map.of("pullRequest", 1, "draft", false, "deliveredAttempt", 1)));
    gh.failPullRequests("bad token " + SECRET);

    run("status", "--refresh");

    assertThat(stderr.toString()).contains("[redacted]").doesNotContain(SECRET);
  }

  @Test
  void refreshSkippedWhileDaemonHoldsTheLock() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    try (var lock = InstanceLock.tryAcquire(new NoirHome(tmp).lock()).orElseThrow()) {
      var out = run("status", "--refresh");
      assertThat(out).contains(ID);
    }
    assertThat(refreshed).isEmpty();
    assertThat(stderr.toString())
        .isEqualTo("noir run is active; it records PR dispositions" + System.lineSeparator());
  }

  @Test
  void refreshReleasesTheLock() {
    run("status", "--refresh");
    assertThat(refreshed).hasSize(1);
    try (var lock = InstanceLock.tryAcquire(new NoirHome(tmp).lock()).orElse(null)) {
      assertThat(lock).isNotNull();
    }
  }

  @Test
  void jsonPutsItemsBeforeMetrics() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var names = new ArrayList<String>();
    Json.mapper().readTree(run("status", "--json")).fieldNames().forEachRemaining(names::add);
    assertThat(names).containsExactly("items", "metrics");
  }

  @Test
  void statusPrintsExperimentBlock() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var out = run("status");
    assertThat(out)
        .contains("\n\nExperiment (1 item)\n")
        .contains("Approval time (median) n/a")
        .contains("Seal time (median)     n/a")
        .contains("Seal rejections        none")
        .contains("Auto-merge             not eligible: 0/20");
    assertThat(run("status")).startsWith("ID");
  }

  @Test
  void statusJsonIncludesMetrics() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/metrics/items").asInt()).isEqualTo(1);
    assertThat(node.at("/metrics/firstPassRate").isNull()).isTrue();
    assertThat(node.at("/metrics/autoMerge/required").asInt()).isEqualTo(20);
    assertThat(node.at("/metrics/autoMerge/eligible").asBoolean()).isFalse();
    assertThat(node.at("/metrics/byState/SEALED").asInt()).isEqualTo(1);
  }

  @Test
  void jsonEmptyStillHasMetrics() throws Exception {
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/metrics/items").asInt()).isZero();
  }

  @Test
  void unreadableItemIsSkippedByMetrics() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var noWorkItem = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1X";
    java.nio.file.Files.createDirectories(store.itemDir(noWorkItem));
    var badEvents = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1Y";
    store.writeWorkItem(WorkItem.sealed(badEvents, "jwcarman/demo", 8));
    java.nio.file.Files.writeString(
        store.itemDir(badEvents).resolve("events.jsonl"), "{not json\n{\"v\":1}\n");

    var node = Json.mapper().readTree(run("status", "--json"));

    assertThat(node.at("/items").size()).isEqualTo(3);
    assertThat(node.at("/metrics/items").asInt()).isEqualTo(1);
    assertThat(node.at("/metrics/byState/SEALED").asInt()).isEqualTo(1);
    assertThat(run("status"))
        .contains(noWorkItem)
        .contains("UNREADABLE")
        .contains("Experiment (1 item)");
  }

  private String run(String... args) {
    var out = new StringWriter();
    var ops =
        new NoirCli.Operations(
            home ->
                (once, listener) -> {
                  throw new AssertionError("run must not be invoked");
                },
            () -> gh,
            () -> Set.of(SECRET),
            home -> {
              refreshed.add(home);
              return new Dispositions(store, gh, new NoBundles(), Clock.systemUTC());
            });
    int code =
        NoirCli.execute(
            new NoirHome(tmp), new PrintWriter(out), new PrintWriter(stderr), ops, args);
    assertThat(code).as(stderr.toString()).isZero();
    return out.toString();
  }

  static final class NoBundles implements Bundles {
    @Override
    public List<String> commitsSince(Path bundle, String baseSha, String branch) {
      throw new UnsupportedOperationException();
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
}
