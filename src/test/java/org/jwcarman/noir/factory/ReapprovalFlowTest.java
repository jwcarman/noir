package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.build.BuildRunner;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.FakeBranchPublisher;
import org.jwcarman.noir.docker.FakeContainerPort;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.git.LocalOriginGit;
import org.jwcarman.noir.git.RepoMirror;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.NoirLabels;
import org.jwcarman.noir.grade.GradeRunner;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.measure.MeasureRunner;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.seal.ApprovalComments;
import org.jwcarman.noir.seal.ApprovalSealer;
import org.jwcarman.noir.seal.Examiner;
import org.jwcarman.noir.seal.SealArtifactsFixture;
import org.jwcarman.noir.seal.SealCheckRunner;
import org.jwcarman.noir.seal.SuiteSealer;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;

/**
 * Approve, edit after approval, get rejected to DRAFT, revise, approve again, seal: the real
 * Daemon, Factory and ApprovalSealer, with a scripted Examiner and seal checks, a local origin and
 * no Docker. The driver stops before building.
 */
class ReapprovalFlowTest {
  static final String REPO = "jwcarman/demo";
  static final String SECRET = "sk-ant-secret-value";
  static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
  static final Instant APPROVED = Instant.parse("2026-11-02T00:00:00Z");
  static final Instant EDITED = Instant.parse("2026-11-02T01:00:00Z");
  static final Instant REVISED = Instant.parse("2026-11-03T00:00:00Z");
  static final Instant REAPPROVED = Instant.parse("2026-11-03T01:00:00Z");
  static final String STOP = "test driver stops before building";

  @TempDir Path tmp;

  @Test
  void rejectedApprovalIsRevisedReapprovedAndSealed() {
    var home = new NoirHome(tmp.resolve("home"));
    var store = new Store(home);
    var gh = new FakeGitHub().withNoirLabels(REPO);
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), REPO);
    var clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
    var config = configFor(REPO);
    var git = new LocalOriginGit(new GitCli(new ProcessCommandRunner()), origin);
    var itemId = new AtomicReference<String>();
    var examiner =
        new ScriptedAgent((n, work) -> {
          write(
              work.resolve("out/holdout/noir/holdout/T.java"),
              "package "
                  + ItemId.holdoutPackage(itemId.get())
                  + ";\nimport org.junit.jupiter.api.*;\nclass T {\n"
                  + "  @Test @Tag(\"R1\") void a() {}\n  @Test @Tag(\"R2\") void b() {}\n}\n");
          write(
              work.resolve("out/reference/src/main/java/com/example/api/Foo.java"),
              "package com.example.api;\npublic interface Foo {}\n");
        });
    SealCheckRunner checks = (inputs, out) -> SealArtifactsFixture.good(out);
    var sealer =
        new ApprovalSealer(
            store,
            gh,
            new RepoMirror(git, home.repos()),
            git,
            new SuiteSealer(
                store,
                new Examiner(examiner, config),
                checks,
                config,
                clock,
                Retry.forConfig(config, d -> {})),
            config,
            clock,
            Set.of(SECRET));
    var delivery = new GitHubDelivery(gh, new FakeBranchPublisher());
    var factory =
        new Factory(
            store,
            delivery,
            sealer,
            unusedBuilder(),
            unusedMeasurer(),
            unusedGrader(),
            new DaemonTest.NoTipBundles(),
            config,
            "sha256:grader",
            clock,
            new Retry(1, Duration.ZERO, Duration.ZERO, d -> {}),
            Set.of(SECRET));
    ItemDriver stopBeforeBuilding =
        id -> {
          if (store.readWorkItem(id).state() == State.SEALED) {
            throw new IllegalStateException(STOP);
          }
          return factory.advance(id);
        };
    var errors = new ArrayList<String>();
    var daemon =
        new Daemon(
            store,
            gh,
            new FakeContainerPort(spec -> null),
            "noir.run=reapproval",
            stopBeforeBuilding,
            new Reconciler(store, gh, delivery, clock, Set.of(SECRET)),
            new Dispositions(store, gh, new DaemonTest.NoTipBundles(), clock),
            config,
            d -> {},
            clock,
            new SplittableRandom(7),
            Set.of(SECRET),
            new Daemon.Listener() {
              @Override
              public void transition(StateChange change) {}

              @Override
              public void error(String id, String message) {
                errors.add(id + ": " + message);
              }

              @Override
              public void warning(String message) {
                errors.add("warning: " + message);
              }

              @Override
              public void created(String id, String repo, int issue) {
                itemId.set(id);
              }
            });

    // Approved, then edited after the approval: rejected back to DRAFT.
    var spec = Specs.valid();
    var original = SpecRenderer.issueBody(spec, Specs.stubsFor(spec));
    int issue = gh.createIssue(REPO, spec.title(), original, List.of());
    gh.createdAt(issue, CREATED);
    gh.addLabel(issue, NoirLabels.READY, "alice", APPROVED);
    gh.editIssue(issue, spec.title(), original.replace("LRU", "least-recently-used"), EDITED);

    daemon.runOnce();

    assertThat(errors).isEmpty();
    assertThat(store.itemIds()).containsExactly(itemId.get());
    var id = itemId.get();
    assertThat(store.readWorkItem(id).state()).isEqualTo(State.DRAFT);
    assertThat(gh.comments(issue)).containsExactly(ApprovalComments.editedAfterApproval());
    assertThat(gh.labels(issue)).containsExactly("noir:draft");
    assertThat(examiner.requests()).isEmpty();

    // Revised while in DRAFT: nothing runs without a new approval.
    var revised =
        spec.withIntent("Callers need a size-bounded cache that evicts the oldest entry.");
    var revisedBody = SpecRenderer.issueBody(revised, Specs.stubsFor(revised));
    gh.editIssue(issue, revised.title(), revisedBody, REVISED);

    daemon.runOnce();

    assertThat(store.readWorkItem(id).state()).isEqualTo(State.DRAFT);
    assertThat(examiner.requests()).isEmpty();

    // Approved again: the same item seals the revised body.
    gh.addLabel(issue, NoirLabels.READY, "alice", REAPPROVED);

    daemon.runOnce();

    assertThat(errors).singleElement().asString().isEqualTo(id + ": " + STOP);
    assertThat(store.itemIds()).containsExactly(id);
    assertThat(store.findItems(REPO, issue)).containsExactly(id);
    assertThat(store.readWorkItem(id).state()).isEqualTo(State.SEALED);
    assertThat(gh.labels(issue)).containsExactlyInAnyOrder("noir:ready", "noir:building");
    var events = store.readEvents(id);
    assertThat(transitions(events))
        .containsExactly("SEALING>DRAFT", "DRAFT>SEALING", "SEALING>SEALED");
    // The edited-after-approval check runs before an approval is recorded, so the only
    // approved event is the re-approval's, and it carries the revised body.
    var revisedSha = Commitments.sha256Hex(IssueSpec.normalize(revisedBody));
    assertThat(events)
        .filteredOn(e -> e.type().equals("approved"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.data()).containsEntry("bodySha256", revisedSha);
          assertThat(e.data()).containsEntry("approvedAt", REAPPROVED.toString());
          assertThat(e.data()).containsEntry("sealedEditedAt", REVISED.toString());
        });
    assertThat(events)
        .filteredOn(e -> e.type().equals("sealed"))
        .singleElement()
        .satisfies(e -> assertThat(e.data()).containsEntry("bodySha256", revisedSha));
    assertThat(events).filteredOn(e -> e.type().equals("commitment_posted")).hasSize(1);
    assertThat(gh.comments(issue))
        .hasSize(2)
        .last()
        .asString()
        .startsWith("noir commitment `")
        .contains(revisedSha);
    assertThat(examiner.requests()).hasSize(1);
  }

  static List<String> transitions(List<Event> events) {
    return events.stream()
        .filter(e -> e.type().equals("transition"))
        .map(e -> e.data().get("from") + ">" + e.data().get("to"))
        .toList();
  }

  static NoirConfig configFor(String repo) {
    var d = NoirConfig.defaults();
    return new NoirConfig(
        List.of(repo),
        d.qualityBar(),
        d.sealDiscrimination(),
        d.budgets(),
        d.reworkRetries(),
        d.pollInterval(),
        d.infraRetries(),
        d.limits(),
        d.images(),
        d.timeouts(),
        d.examiner());
  }

  static BuildRunner unusedBuilder() {
    return (spec, stubs, input, branch, message, feedback, dir) -> {
      throw new AssertionError("builder must not run");
    };
  }

  static MeasureRunner unusedMeasurer() {
    return (spec, bundle, branch, stubs, out) -> {
      throw new AssertionError("measurer must not run");
    };
  }

  static GradeRunner unusedGrader() {
    return (classes, deps, holdout, pkg, out) -> {
      throw new AssertionError("grader must not run");
    };
  }

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
