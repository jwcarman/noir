package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class DispositionsTest {
  static final String REPO = "jwcarman/demo";
  static final String A = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A";
  static final String B = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1B";
  static final String TIP = "c0ffee0000000000000000000000000000000000";

  @TempDir Path home;
  Store store;
  final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
  final FakeGitHub gh = new FakeGitHub().withNoirLabels("jwcarman/demo");
  final List<Integer> lookups = new ArrayList<>();
  final List<String> warnings = new ArrayList<>();
  Dispositions dispositions;
  boolean tipFails;

  /** The fake, counting every pull request lookup. */
  GitHubPort counting() {
    return (GitHubPort)
        java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {GitHubPort.class},
            (proxy, method, args) -> {
              if (method.getName().equals("pullRequest")) {
                lookups.add((Integer) args[1]);
              }
              try {
                return method.invoke(gh, args);
              } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(home));
    dispositions =
        new Dispositions(
            store,
            counting(),
            new Bundles() {
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
                if (tipFails) {
                  throw new IllegalStateException("git exploded");
                }
                return TIP;
              }
            },
            clock);
  }

  /**
   * A delivered item with a PR (number returned); the bundle exists unless {@code bundle} false.
   */
  int delivered(String id, boolean draft, boolean bundle) {
    int issue = 7;
    var item = WorkItem.sealed(id, REPO, issue).withState(State.PR_OPEN).withAttempt(2);
    store.writeWorkItem(item);
    var dir = store.itemDir(id);
    var spec = Specs.valid().withId(id);
    SpecJson.write(spec, dir.resolve("spec.json"));
    int pr =
        gh.createPullRequest(REPO, "main", BranchNames.of(issue, spec.title()), "t", "b", draft);
    try {
      if (bundle) {
        var path = new AttemptPaths(dir, 1).bundle();
        Files.createDirectories(path.getParent());
        Files.writeString(path, "bundle");
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    store.appendEvent(
        Event.of(
            clock,
            id,
            "delivered",
            2,
            Map.of("pullRequest", pr, "draft", draft, "deliveredAttempt", 1)));
    return pr;
  }

  Event only(String id) {
    var found =
        store.readEvents(id).stream().filter(e -> e.type().equals("pr_disposition")).toList();
    assertThat(found).hasSize(1);
    return found.get(0);
  }

  @Test
  void mergedAtDeliveredHeadIsAccepted() {
    int pr = delivered(A, false, true);
    gh.merge(pr, TIP);

    assertThat(dispositions.refresh(warnings::add)).containsExactly(A);

    var e = only(A);
    assertThat(e.attempt()).isEqualTo(2);
    assertThat(e.data())
        .containsEntry("disposition", "accepted")
        .containsEntry("pullRequest", pr)
        .containsEntry("draft", false)
        .containsEntry("headSha", TIP)
        .containsEntry("deliveredSha", TIP)
        .containsEntry("mergedAt", Instant.EPOCH.toString());
  }

  @Test
  void mergedWithExtraCommitsIsModified() {
    int pr = delivered(A, false, true);
    gh.merge(pr, "deadbeef");

    dispositions.refresh(warnings::add);

    assertThat(only(A).data())
        .containsEntry("disposition", "modified")
        .containsEntry("headSha", "deadbeef")
        .containsEntry("deliveredSha", TIP);
  }

  @Test
  void closedUnmergedIsRejected() {
    int pr = delivered(A, false, true);
    gh.closePullRequest(pr);

    dispositions.refresh(warnings::add);

    assertThat(only(A).data())
        .containsEntry("disposition", "rejected")
        .doesNotContainKey("mergedAt");
  }

  @Test
  void openPrRecordsNothing() {
    delivered(A, false, true);

    assertThat(dispositions.refresh(warnings::add)).isEmpty();
    assertThat(store.readEvents(A)).noneMatch(e -> e.type().equals("pr_disposition"));
  }

  @Test
  void dispositionIsRecordedOnce() {
    int pr = delivered(A, false, true);
    gh.merge(pr, TIP);
    dispositions.refresh(warnings::add);
    lookups.clear();

    assertThat(dispositions.refresh(warnings::add)).isEmpty();

    assertThat(lookups).isEmpty();
    only(A);
  }

  @Test
  void missingBundleMergedIsModified() {
    int pr = delivered(A, false, false);
    gh.merge(pr, TIP);

    dispositions.refresh(warnings::add);

    assertThat(only(A).data())
        .containsEntry("disposition", "modified")
        .doesNotContainKey("deliveredSha");
  }

  @Test
  void unreadableBundleWarnsAndRecordsNothing() {
    int pr = delivered(A, false, true);
    gh.merge(pr, TIP);
    tipFails = true;

    assertThat(dispositions.refresh(warnings::add)).isEmpty();

    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0)).startsWith(A).contains("git exploded");
    assertThat(store.readEvents(A)).noneMatch(e -> e.type().equals("pr_disposition"));

    tipFails = false;
    assertThat(dispositions.refresh(warnings::add)).containsExactly(A);
    assertThat(only(A).data()).containsEntry("disposition", "accepted");
  }

  @Test
  void oneItemFailingDoesNotStopTheOthers() {
    delivered(A, false, true);
    int pr = delivered(B, false, true);
    gh.merge(pr, TIP);
    // A's PR vanishes: its lookup fails.
    store.appendEvent(
        Event.of(
            clock,
            A,
            "delivered",
            2,
            Map.of("pullRequest", 99, "draft", false, "deliveredAttempt", 1)));

    assertThat(dispositions.refresh(warnings::add)).containsExactly(B);

    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0)).startsWith(A);
    only(B);
  }

  @Test
  void draftPrDispositionKeepsDraftFlag() {
    int pr = delivered(A, true, true);
    gh.merge(pr, TIP);

    dispositions.refresh(warnings::add);

    assertThat(only(A).data()).containsEntry("draft", true);
  }

  @Test
  void commentOnlyDeliveryIsSkipped() {
    store.writeWorkItem(WorkItem.sealed(A, REPO, 7).withState(State.PR_OPEN));
    store.appendEvent(Event.of(clock, A, "delivered", 1, Map.of("comment", true)));

    assertThat(dispositions.refresh(warnings::add)).isEmpty();
    assertThat(lookups).isEmpty();
  }
}
