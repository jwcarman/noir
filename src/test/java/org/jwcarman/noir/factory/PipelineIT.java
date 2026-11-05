package org.jwcarman.noir.factory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.build.ContainerBuildRunner;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.LocalBranchPublishers;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.git.LocalOriginGit;
import org.jwcarman.noir.git.RepoMirror;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.NoirLabels;
import org.jwcarman.noir.grade.ContainerGradeRunner;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.measure.ContainerMeasureRunner;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.seal.ApprovalComments;
import org.jwcarman.noir.seal.ApprovalSealer;
import org.jwcarman.noir.seal.ContainerSealCheckRunner;
import org.jwcarman.noir.seal.Examiner;
import org.jwcarman.noir.seal.SuiteSealer;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * The whole pipeline end to end: real builder, measurement, and grader containers, real git, a
 * {@link FakeGitHub}, a local bare origin, and a {@link ScriptedAgent} standing in for Claude. Each
 * scenario seals the Plan 2 Counter item and drives it with {@code reworkRetries = 1}.
 */
@Tag("docker")
class PipelineIT {
  static final DockerCli docker = new DockerCli(new ProcessCommandRunner());
  static final NoirConfig defaults = NoirConfig.defaults();
  static final NoirConfig config =
      new NoirConfig(
          defaults.repos(),
          defaults.qualityBar(),
          defaults.sealDiscrimination(),
          defaults.budgets(),
          1,
          defaults.pollInterval(),
          defaults.infraRetries(),
          defaults.limits(),
          defaults.images(),
          defaults.timeouts(),
          defaults.examiner());
  static final String BRANCH = "noir/1-counter";
  static final String SECRET = "sk-ant-pipeline-it-not-a-real-key";

  /** The sealed holdout-good test method names; none may ever reach the builder. */
  static final List<String> HOLDOUT_NAMES =
      List.of(
          "startsAtZero",
          "incrementAddsOne",
          "incrementTwice",
          "resetReturnsToZero",
          "noir.holdout",
          "nr_01jbx4zq8k3v0m7n2p5r6s9t1w");

  static String graderImageId;

  @BeforeAll
  static void images() {
    Images.ensureBuilder(docker, config.images().builder());
    graderImageId = Images.ensureGrader(docker, config.images().grader());
  }

  @TempDir Path tmp;

  /** The outcome of driving one sealed item with one scripted builder. */
  record Run(SealedItemFixture item, ScriptedAgent agent, WorkItem finalItem, Duration elapsed) {
    List<Event> events() {
      return item.store().readEvents(item.id());
    }

    /** {@code SEALED > BUILDING > ... > PR_OPEN}, with each failure category in parentheses. */
    String path() {
      return pathOf(events());
    }

    List<Event> ofType(String type) {
      return events().stream().filter(e -> e.type().equals(type)).toList();
    }

    Path attempt(int n) {
      return new AttemptPaths(item.itemDir(), n).dir();
    }

    String read(Path p) {
      return readFile(p);
    }
  }

  static String pathOf(List<Event> events) {
    var steps = new ArrayList<String>();
    for (var e : events) {
      if (e.type().equals("transition")) {
        if (steps.isEmpty()) {
          steps.add(String.valueOf(e.data().get("from")));
        }
        var failure = e.data().get("failure");
        steps.add(e.data().get("to") + (failure == null ? "" : "(" + failure + ")"));
      }
    }
    return String.join(" > ", steps);
  }

  static String readFile(Path p) {
    try {
      return Files.readString(p, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Seals the Counter item, then drives it with a builder that copies {@code fixtures/builders/
   * <name>} into its working tree on every call (plus an attempt marker, so every attempt has new
   * work to commit).
   */
  Run drive(String builder) {
    var item = SealedItemFixture.create(tmp);
    var runner = new ProcessCommandRunner();
    var scratch = tmp.resolve("scratch").toAbsolutePath();
    try {
      Files.createDirectories(scratch);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var files = SealedItemFixture.resource("/fixtures/builders/" + builder);
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          SealedItemFixture.copyTree(files, repo);
          try {
            Files.writeString(repo.resolve("ATTEMPT.txt"), "attempt " + n + "\n");
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    var factory =
        new Factory(
            item.store(),
            new GitHubDelivery(
                item.github(), LocalBranchPublishers.pushingTo(runner, scratch, item.origin())),
            sealedItem -> {
              throw new AssertionError("sealing not expected");
            },
            new ContainerBuildRunner(docker, agent, config),
            new ContainerMeasureRunner(docker, config),
            new ContainerGradeRunner(docker, config),
            new GitBundles(runner, scratch),
            config,
            graderImageId,
            Clock.systemUTC(),
            new Retry(1, Duration.ZERO, Duration.ZERO, d -> {}),
            Set.of(SECRET));
    long start = System.nanoTime();
    factory.drive(item.id());
    var elapsed = Duration.ofNanos(System.nanoTime() - start);
    var run = new Run(item, agent, item.store().readWorkItem(item.id()), elapsed);
    System.out.printf("[PipelineIT] %s: %s in %ds%n", builder, run.path(), elapsed.toSeconds());
    var feedback = run.attempt(1).resolve("FEEDBACK.md");
    if (Files.isRegularFile(feedback)) {
      System.out.printf("[PipelineIT] %s attempt 1 feedback:%n%s%n", builder, run.read(feedback));
    }
    return run;
  }

  static String originBranchSha(Path origin, String branch) {
    return GitFixtures.git(origin, "rev-parse", "--verify", "refs/heads/" + branch).strip();
  }

  static String originFile(Path origin, String branch, String path) {
    return GitFixtures.git(origin, "show", branch + ":" + path);
  }

  @Test
  void correctBuilderOpensPullRequest() {
    var run = drive("correct");

    assertThat(run.finalItem().state()).as(run.path()).isEqualTo(State.PR_OPEN);
    assertThat(run.path()).isEqualTo("SEALED > BUILDING > MEASURING > GRADING > PR_OPEN");
    assertThat(run.agent().requests()).hasSize(1);

    var origin = run.item().origin();
    assertThat(originBranchSha(origin, BRANCH)).hasSize(40);
    assertThat(originFile(origin, BRANCH, SealedItemFixture.COUNTER_PATH)).contains("count++;");

    var github = run.item().github();
    assertThat(github.pullRequests())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.draft()).isFalse();
          assertThat(pr.head()).isEqualTo(BRANCH);
          assertThat(pr.base()).isEqualTo("main");
          assertThat(pr.title()).isEqualTo("Counter");
          assertThat(pr.body())
              .contains("Closes #1")
              .contains("R1: 3/3 passed")
              .contains("R2: 1/1 passed")
              .contains("| Line coverage | 100.0% |")
              .doesNotContain(SECRET);
        });
    assertThat(github.issue(1).labels()).containsExactlyInAnyOrder("noir:ready", "noir:pr-open");
  }

  @Test
  void wrongBuilderFailsWithDraftPr() {
    var run = drive("wrong");

    assertThat(run.finalItem().state()).as(run.path()).isEqualTo(State.FAILED);
    assertThat(run.path())
        .isEqualTo(
            "SEALED > BUILDING > MEASURING > GRADING > REWORK(BEHAVIOR_FAILURE)"
                + " > BUILDING > MEASURING > GRADING > FAILED(BEHAVIOR_FAILURE)");
    assertThat(run.ofType("grade"))
        .hasSize(2)
        .allSatisfy(e -> assertThat(e.data().get("result")).isEqualTo("failed"));

    // The second attempt's FEEDBACK.md (what the builder saw) has counts, never holdout names.
    var feedback = run.read(run.attempt(2).resolve("build/FEEDBACK.md"));
    assertThat(feedback).contains("R1: 1/3 passed").contains("R2: 1/1 passed");
    for (var name : HOLDOUT_NAMES) {
      assertThat(feedback).doesNotContain(name);
    }
    assertThat(run.agent().requests().get(1).prompt()).contains("R1: 1/3 passed");

    var github = run.item().github();
    assertThat(github.pullRequests())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.draft()).isTrue();
          assertThat(pr.head()).isEqualTo(BRANCH);
          assertThat(pr.body()).contains("Closes #1").contains("R1: 1/3 passed");
        });
    assertThat(originFile(run.item().origin(), BRANCH, "ATTEMPT.txt")).isEqualTo("attempt 2\n");
    assertThat(github.issue(1).labels()).containsExactlyInAnyOrder("noir:ready", "noir:failed");
  }

  @Test
  void holdoutPackageSquatterIsContractViolation() {
    var run = drive("holdout-squatter");

    var measurements = run.ofType("measurement");
    assertThat(measurements).as(run.path()).isNotEmpty();
    assertThat(measurements.get(0).data())
        .containsEntry("passed", false)
        .containsEntry("category", "CONTRACT_VIOLATION");
    assertThat(run.path())
        .isEqualTo(
            "SEALED > BUILDING > MEASURING > REWORK(CONTRACT_VIOLATION)"
                + " > BUILDING > MEASURING > FAILED(CONTRACT_VIOLATION)");
    // The squatter never reached the grader.
    assertThat(run.ofType("grade")).isEmpty();
    assertThat(run.read(run.attempt(1).resolve("FEEDBACK.md")))
        .contains(
            "source in reserved package noir.holdout: src/main/java/noir/holdout/"
                + "nr_01jbx4zq8k3v0m7n2p5r6s9t1w/CounterTest.java");
    assertThat(run.item().github().pullRequests())
        .allSatisfy(pr -> assertThat(pr.draft()).isTrue());
  }

  /**
   * The service registration and the auto-detection switch ship in the builder's classes. The
   * forbidden scan rejects them in measurement (CONTRACT_VIOLATION), so the swallowing extension
   * never reaches the grader; were it to, the grader's own scan fails the grade closed.
   */
  @Test
  void junitExtensionCannotSwallowHoldoutFailures() {
    var run = drive("extension-cheat");

    assertThat(run.finalItem().state()).as(run.path()).isEqualTo(State.FAILED);
    assertThat(run.path())
        .isEqualTo(
            "SEALED > BUILDING > MEASURING > REWORK(CONTRACT_VIOLATION)"
                + " > BUILDING > MEASURING > FAILED(CONTRACT_VIOLATION)");
    assertThat(run.ofType("grade")).isEmpty();
    assertThat(run.read(run.attempt(1).resolve("FEEDBACK.md")))
        .contains(
            "forbidden in build output: /work/out/export/classes/META-INF/services/"
                + "org.junit.jupiter.api.extension.Extension")
        .contains("forbidden in build output: /work/out/export/classes/junit-platform.properties");
    assertThat(run.item().github().pullRequests())
        .singleElement()
        .satisfies(pr -> assertThat(pr.draft()).isTrue());
  }

  /**
   * The POM's skipTests and testFailureIgnore only touch the builder's own Maven build: its tests
   * never run, so noir's JaCoCo agent records no coverage and measurement reports a shortfall. The
   * grader runs no Maven at all, so the POM could never have skipped it.
   */
  @Test
  void pomCannotSkipTheGrader() {
    var run = drive("pom-cheat");

    assertThat(run.finalItem().state()).as(run.path()).isEqualTo(State.FAILED);
    assertThat(run.path())
        .isEqualTo(
            "SEALED > BUILDING > MEASURING > REWORK(QUALITY_SHORTFALL)"
                + " > BUILDING > MEASURING > FAILED(QUALITY_SHORTFALL)");
    assertThat(run.ofType("measurement").get(0).data()).containsEntry("lineCoverage", 0.0);
    assertThat(run.read(run.attempt(1).resolve("FEEDBACK.md")))
        .contains("| com.example.api.Counter | 0.0% |");
    assertThat(run.item().github().pullRequests())
        .singleElement()
        .satisfies(pr -> assertThat(pr.draft()).isTrue());
  }

  /** The outcome of one daemon pass over an approved issue, starting from an empty store. */
  record Approval(
      Store store,
      FakeGitHub github,
      ScriptedAgent examiner,
      String itemId,
      int issue,
      Path origin) {
    String path() {
      return pathOf(store.readEvents(itemId));
    }
  }

  /**
   * Approves the Counter issue (label added 2026-11-02; edited at {@code editedAt} if non-null),
   * then lets the real daemon discover, seal, and build it with the real Examiner flow scripted.
   */
  Approval approve(Instant editedAt) {
    var runner = new ProcessCommandRunner();
    var home = new NoirHome(tmp.resolve("home"));
    var store = new Store(home);
    var github = new FakeGitHub();
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), SealedItemFixture.REPO);
    var scratch = tmp.resolve("scratch").toAbsolutePath();
    try {
      Files.createDirectories(scratch);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    var spec =
        Specs.valid()
            .withTitle("Counter")
            .withApiStubs(List.of(SealedItemFixture.COUNTER_PATH))
            .withRequirements(
                List.of(
                    new Requirement(
                        "R1",
                        "increment adds one; a new counter reads zero",
                        List.of(new Example("a new counter", "increment()", "get() returns 1"))),
                    new Requirement(
                        "R2",
                        "reset sets the count to zero",
                        List.of(new Example("a counter at 1", "reset()", "get() returns 0")))));
    var stubs =
        List.of(
            new StubSource(
                SealedItemFixture.COUNTER_PATH,
                readFile(
                    SealedItemFixture.sealFixture("stubs")
                        .resolve(SealedItemFixture.COUNTER_PATH))));
    int issue =
        github.createIssue(
            SealedItemFixture.REPO, spec.title(), SpecRenderer.issueBody(spec, stubs), List.of());
    github.createdAt(issue, Instant.parse("2026-11-01T00:00:00Z"));
    github.addLabel(issue, NoirLabels.READY, "alice", Instant.parse("2026-11-02T00:00:00Z"));
    if (editedAt != null) {
      github.editIssue(
          issue, spec.title(), SpecRenderer.issueBody(spec, stubs) + "\nedited\n", editedAt);
    }

    var cfg =
        new NoirConfig(
            List.of(SealedItemFixture.REPO),
            config.qualityBar(),
            config.sealDiscrimination(),
            config.budgets(),
            config.reworkRetries(),
            config.pollInterval(),
            config.infraRetries(),
            config.limits(),
            config.images(),
            config.timeouts(),
            config.examiner());
    var clock = Clock.systemUTC();
    var git = new LocalOriginGit(new GitCli(runner), origin);
    var approvedId = new AtomicReference<String>();
    var examinerAgent =
        new ScriptedAgent((n, work) -> {
          // The holdout package is named for the item; the daemon picks the id, so rename the
          // fixture.
          var holdout = work.resolve("out/holdout");
          SealedItemFixture.copyTree(SealedItemFixture.sealFixture("holdout-good"), holdout);
          var fixtureId = "nr_01jbx4zq8k3v0m7n2p5r6s9t1w";
          var id = ItemId.holdoutPackage(approvedId.get()).substring("noir.holdout.".length());
          var dir = holdout.resolve("noir/holdout");
          try {
            var test = dir.resolve(fixtureId).resolve("CounterTest.java");
            Files.writeString(test, readFile(test).replace(fixtureId, id), UTF_8);
            Files.move(dir.resolve(fixtureId), dir.resolve(id));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          SealedItemFixture.copyTree(
              SealedItemFixture.sealFixture("reference"), work.resolve("out/reference"));
        });
    var sealer =
        new ApprovalSealer(
            store,
            github,
            new RepoMirror(git, home.repos()),
            git,
            new SuiteSealer(
                store,
                new Examiner(examinerAgent, cfg),
                new ContainerSealCheckRunner(docker, cfg),
                cfg,
                clock),
            cfg,
            clock,
            Set.of(SECRET));
    var files = SealedItemFixture.resource("/fixtures/builders/correct");
    var builder =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          SealedItemFixture.copyTree(files, repo);
          try {
            Files.writeString(repo.resolve("ATTEMPT.txt"), "attempt " + n + "\n");
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    var delivery =
        new GitHubDelivery(github, LocalBranchPublishers.pushingTo(runner, scratch, origin));
    var factory =
        new Factory(
            store,
            delivery,
            sealer,
            new ContainerBuildRunner(docker, builder, cfg),
            new ContainerMeasureRunner(docker, cfg),
            new ContainerGradeRunner(docker, cfg),
            new GitBundles(runner, scratch),
            cfg,
            graderImageId,
            clock,
            new Retry(1, Duration.ZERO, Duration.ZERO, d -> {}),
            Set.of(SECRET));
    var created = new ArrayList<String>();
    var errors = new ArrayList<String>();
    var daemon =
        new Daemon(
            store,
            github,
            docker,
            "pipeline-it",
            factory,
            new Reconciler(store, github, delivery, clock, Set.of(SECRET)),
            new Dispositions(store, github, new GitBundles(runner, scratch), clock),
            cfg,
            d -> {},
            clock,
            new SecureRandom(),
            Set.of(SECRET),
            new Daemon.Listener() {
              @Override
              public void transition(StateChange change) {}

              @Override
              public void error(String itemId, String message) {
                errors.add(itemId + ": " + message);
              }

              @Override
              public void warning(String message) {
                errors.add("warning: " + message);
              }

              @Override
              public void created(String itemId, String repo, int n) {
                created.add(itemId);
                approvedId.set(itemId);
              }
            });
    daemon.runOnce();
    assertThat(errors).isEmpty();
    assertThat(created).hasSize(1);
    var result = new Approval(store, github, examinerAgent, created.getFirst(), issue, origin);
    System.out.printf("[PipelineIT] approval: %s%n", result.path());
    return result;
  }

  @Test
  void approvedIssue() {
    var a = approve(null);

    assertThat(a.path())
        .as(String.valueOf(a.github().comments(a.issue())))
        .isEqualTo("SEALING > SEALED > BUILDING > MEASURING > GRADING > PR_OPEN");
    assertThat(a.github().comments(a.issue()))
        .singleElement()
        .satisfies(c -> assertThat(c).startsWith("noir commitment `"));
    assertThat(a.github().labels(a.issue())).contains(NoirLabels.READY);
    assertThat(a.github().pullRequests()).singleElement();
    assertThat(originBranchSha(a.origin(), "noir/" + a.issue() + "-counter")).hasSize(40);
  }

  @Test
  void approvedIssueEditedAfterApproval() {
    var a = approve(Instant.parse("2026-11-03T00:00:00Z"));

    assertThat(a.path()).isEqualTo("SEALING > DRAFT");
    assertThat(a.github().comments(a.issue()))
        .containsExactly(ApprovalComments.editedAfterApproval());
    assertThat(a.github().labels(a.issue()))
        .doesNotContain(NoirLabels.READY)
        .contains("noir:draft");
    assertThat(a.examiner().requests()).isEmpty();
    assertThat(a.github().pullRequests()).isEmpty();
  }
}
