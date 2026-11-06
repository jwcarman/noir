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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import org.assertj.core.api.InstanceOfAssertFactories;
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
   * The lock check rejects any change to pom.xml (here the cheat adds skipTests to it) before
   * measurement runs, so the grader is never reached.
   */
  @Test
  void pomEditIsRejectedByLockCheck() {
    var run = drive("pom-cheat");

    assertThat(run.finalItem().state()).as(run.path()).isEqualTo(State.FAILED);
    assertThat(run.path())
        .isEqualTo(
            "SEALED > BUILDING > MEASURING > REWORK(CONTRACT_VIOLATION)"
                + " > BUILDING > MEASURING > FAILED(CONTRACT_VIOLATION)");
    assertThat(run.ofType("grade")).isEmpty();
    assertThat(run.read(run.attempt(1).resolve("FEEDBACK.md"))).contains("`pom.xml`");
    assertThat(run.item().github().pullRequests())
        .singleElement()
        .satisfies(pr -> assertThat(pr.draft()).isTrue());
  }

  /**
   * Adding {@code .mvn/maven.config} (here {@code -DskipTests} and {@code
   * -Dmaven.test.failure.ignore=true}; equally {@code -Dtest=!CounterTest}) could let locked tests
   * fail while {@code mvn verify} exits 0, so the lock check rejects the new file before
   * measurement runs and the grader is never reached.
   */
  @Test
  void mavenConfigCannotSkipTheGrader() {
    var run = drive("maven-config-cheat");

    assertThat(run.finalItem().state()).as(run.path()).isEqualTo(State.FAILED);
    assertThat(run.path())
        .isEqualTo(
            "SEALED > BUILDING > MEASURING > REWORK(CONTRACT_VIOLATION)"
                + " > BUILDING > MEASURING > FAILED(CONTRACT_VIOLATION)");
    assertThat(run.ofType("grade")).isEmpty();
    assertThat(run.read(run.attempt(1).resolve("FEEDBACK.md")))
        .contains("You added `.mvn/maven.config`.");
    assertThat(run.item().github().pullRequests())
        .singleElement()
        .satisfies(pr -> assertThat(pr.draft()).isTrue());
  }

  /** The outcome of one daemon pass over an approved issue, starting from an empty store. */
  record Approval(
      Store store,
      FakeGitHub github,
      ScriptedAgent examiner,
      ScriptedAgent builder,
      String itemId,
      int issue,
      Path origin) {
    String path() {
      return pathOf(store.readEvents(itemId));
    }

    Path attempt(int n) {
      return new AttemptPaths(store.itemDir(itemId), n).dir();
    }
  }

  /** Every regular file under {@code dir}, keyed by its slash-separated relative path. */
  static Map<String, String> treeOf(Path dir) {
    try (var paths = Files.walk(dir)) {
      var files = new TreeMap<String, String>();
      for (var p : paths.filter(Files::isRegularFile).toList()) {
        files.put(dir.relativize(p).toString().replace('\\', '/'), readFile(p));
      }
      return files;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One approval scenario: the base tree ({@code null} for the GitFixtures placeholder base), the
   * issue body, the Examiner's holdout and reference fixtures, the builder script, and the number
   * of Examiner attempts.
   */
  record Scenario(
      Path base,
      String title,
      String body,
      Path holdout,
      Path reference,
      BiConsumer<Integer, Path> builder,
      int examinerAttempts) {}

  /** A builder that copies {@code files} into its working tree, plus an attempt marker. */
  static BiConsumer<Integer, Path> copying(Path files) {
    return copying(n -> files);
  }

  /** A builder that copies the tree {@code filesFor} names for each attempt, plus a marker. */
  static BiConsumer<Integer, Path> copying(IntFunction<Path> filesFor) {
    return (n, work) -> {
      var repo = work.resolve("repo");
      SealedItemFixture.copyTree(filesFor.apply(n), repo);
      try {
        Files.writeString(repo.resolve("ATTEMPT.txt"), "attempt " + n + "\n");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  /**
   * Approves the Counter issue (label added 2026-11-02; edited at {@code editedAt} if non-null),
   * then lets the real daemon discover, seal, and build it with the real Examiner flow scripted.
   */
  Approval approve(Instant editedAt) {
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
    var body = SpecRenderer.issueBody(spec, stubs);
    return approve(
        new Scenario(
            null,
            spec.title(),
            body,
            SealedItemFixture.sealFixture("holdout-good"),
            SealedItemFixture.sealFixture("reference"),
            copying(SealedItemFixture.resource("/fixtures/builders/correct")),
            config.examiner().attempts()),
        editedAt == null ? null : body + "\nedited\n",
        editedAt);
  }

  /** Approves the scenario's issue and runs one daemon pass over it (see above). */
  Approval approve(Scenario s, String editedBody, Instant editedAt) {
    var runner = new ProcessCommandRunner();
    var home = new NoirHome(tmp.resolve("home"));
    var store = new Store(home);
    var github = new FakeGitHub().withNoirLabels("jwcarman/demo");
    var origin =
        s.base() == null
            ? GitFixtures.originWithCommit(tmp.resolve("remotes"), SealedItemFixture.REPO)
            : GitFixtures.originWithFiles(
                tmp.resolve("remotes"), SealedItemFixture.REPO, treeOf(s.base()));
    var scratch = tmp.resolve("scratch").toAbsolutePath();
    try {
      Files.createDirectories(scratch);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    int issue = github.createIssue(SealedItemFixture.REPO, s.title(), s.body(), List.of());
    github.createdAt(issue, Instant.parse("2026-11-01T00:00:00Z"));
    github.addLabel(issue, NoirLabels.READY, "alice", Instant.parse("2026-11-02T00:00:00Z"));
    if (editedAt != null) {
      github.editIssue(issue, s.title(), editedBody, editedAt);
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
            new NoirConfig.Examiner(
                config.examiner().model(),
                config.examiner().maxTurns(),
                config.examiner().wallClock(),
                s.examinerAttempts()));
    var clock = Clock.systemUTC();
    var git = new LocalOriginGit(new GitCli(runner), origin);
    var approvedId = new AtomicReference<String>();
    var examinerAgent =
        new ScriptedAgent((n, work) -> {
          // The holdout package is named for the item; the daemon picks the id, so rename the
          // fixture.
          var holdout = work.resolve("out/holdout");
          SealedItemFixture.copyTree(s.holdout(), holdout);
          var fixtureId = "nr_01jbx4zq8k3v0m7n2p5r6s9t1w";
          var id = ItemId.holdoutPackage(approvedId.get()).substring("noir.holdout.".length());
          var dir = holdout.resolve("noir/holdout");
          try (var tests = Files.list(dir.resolve(fixtureId))) {
            for (var test : tests.toList()) {
              Files.writeString(test, readFile(test).replace(fixtureId, id), UTF_8);
            }
            Files.move(dir.resolve(fixtureId), dir.resolve(id));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          SealedItemFixture.copyTree(s.reference(), work.resolve("out/reference"));
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
                git,
                cfg,
                clock,
                Retry.forConfig(cfg, d -> {})),
            cfg,
            clock,
            Set.of(SECRET));
    var builder = new ScriptedAgent(s.builder());
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
    var result =
        new Approval(store, github, examinerAgent, builder, created.getFirst(), issue, origin);
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

  static final String COUNTER_TEST_PATH = "src/test/java/com/example/api/CounterTest.java";

  static Path modifyFixture(String name) {
    return SealedItemFixture.resource("/fixtures/modify/" + name);
  }

  /**
   * The modify issue: {@code Counter.java (modify)} with a changed {@code increment} (it stops at
   * {@code limit}) and an added {@code reset()}, listing {@code testsToUpdate}.
   */
  static String modifyBody(String limit, List<String> testsToUpdate) {
    var spec =
        Specs.valid()
            .withTitle("Counter")
            .withIntent("The counter must not run past " + limit + ", and callers need a reset.")
            .withApiStubs(List.of(SealedItemFixture.COUNTER_PATH))
            .withModifies(List.of(SealedItemFixture.COUNTER_PATH))
            .withTestsToUpdate(testsToUpdate)
            .withSchemaVersion(2)
            .withRequirements(
                List.of(
                    new Requirement(
                        "R1",
                        "increment adds one but never takes the count above " + limit,
                        List.of(
                            new Example(
                                "a counter at " + limit, "increment()", "get() returns " + limit))),
                    new Requirement(
                        "R2",
                        "reset sets the count to zero",
                        List.of(new Example("a counter at 2", "reset()", "get() returns 0")))));
    var fragment =
        readFile(modifyFixture("fragments").resolve(SealedItemFixture.COUNTER_PATH))
            .replace("three", limit);
    return SpecRenderer.issueBody(
        spec, List.of(new StubSource(SealedItemFixture.COUNTER_PATH, fragment)));
  }

  Approval approveModify(
      String body, String examiner, BiConsumer<Integer, Path> builder, int examinerAttempts) {
    var a =
        approve(
            new Scenario(
                modifyFixture("base"),
                "Counter",
                body,
                modifyFixture(examiner + "/holdout"),
                modifyFixture(examiner + "/reference"),
                builder,
                examinerAttempts),
            null,
            null);
    var feedback = a.attempt(1).resolve("FEEDBACK.md");
    if (Files.isRegularFile(feedback)) {
      System.out.printf("[PipelineIT] attempt 1 feedback:%n%s%n", readFile(feedback));
    }
    return a;
  }

  @Test
  void modifySpecFixesExistingClass() {
    var a =
        approveModify(
            modifyBody("three", List.of(COUNTER_TEST_PATH)),
            "examiner-cap",
            copying(modifyFixture("builders/fix")),
            config.examiner().attempts());

    assertThat(a.path())
        .as(String.valueOf(a.github().comments(a.issue())))
        .isEqualTo("SEALING > SEALED > BUILDING > MEASURING > GRADING > PR_OPEN");
    assertThat(a.examiner().requests()).hasSize(1);
    assertThat(a.examiner().requests().getFirst().prompt()).contains("## Current source");
    var branch = "noir/" + a.issue() + "-counter";
    assertThat(originFile(a.origin(), branch, SealedItemFixture.COUNTER_PATH))
        .contains("if (count < 3)");
    assertThat(originFile(a.origin(), branch, COUNTER_TEST_PATH)).contains("incrementStopsAtThree");
    // Sealing kept the issue's fragment and spliced it into the base: increment keeps today's
    // body under the new Javadoc, and reset() is appended with the UOE body.
    var itemDir = a.store().itemDir(a.itemId());
    assertThat(readFile(itemDir.resolve("fragments").resolve(SealedItemFixture.COUNTER_PATH)))
        .isEqualTo(readFile(modifyFixture("fragments").resolve(SealedItemFixture.COUNTER_PATH)));
    assertThat(readFile(itemDir.resolve("stubs").resolve(SealedItemFixture.COUNTER_PATH)))
        .isEqualTo(readFile(modifyFixture("spliced").resolve(SealedItemFixture.COUNTER_PATH)));

    // Seal check 3 scored Counter on the lines the reference changed. The base methods no test
    // calls (decrement, isAbove, doubled, halved) keep whole-class PIT below the threshold, so
    // whole-class scoring could never have sealed this spec.
    var pit =
        org.jwcarman.noir.reports.PitMutations.parse(
            itemDir.resolve("examiner/attempt-1/checks/pit/mutations.xml"));
    assertThat(pit.score()).as("whole-class mutation score").isLessThan(0.70);

    // The base Counter has decrement(), which no test calls. Whole-class measurement would
    // report it as an uncovered line and a surviving mutant; changed-lines measurement counts
    // only the lines the attempt added or changed (the capped increment and reset), all of
    // which the builder's tests cover and whose mutants they kill.
    assertThat(a.store().readEvents(a.itemId()))
        .filteredOn(e -> e.type().equals("measurement"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.data()).containsEntry("passed", true);
          assertThat(e.data().get("changedClasses"))
              .asInstanceOf(InstanceOfAssertFactories.LIST)
              .contains("com.example.api.Counter");
          assertThat(e.data())
              .containsEntry("lineCoverage", 1.0)
              .containsEntry("mutationScore", 1.0);
        });

    assertThat(a.github().pullRequests())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.draft()).isFalse();
          assertThat(pr.body())
              .contains(
                  "### Files\n\n"
                      + "- Added: `ATTEMPT.txt` (+1)\n"
                      + "- Modified: `"
                      + SealedItemFixture.COUNTER_PATH
                      + "` (+9 \u22122)\n"
                      + "- Tests updated: `"
                      + COUNTER_TEST_PATH
                      + "` (+17 \u22120)\n")
              .doesNotContain(SECRET);
        });
  }

  @Test
  void builderEditingLockedTestIsReworked() {
    var a =
        approveModify(
            modifyBody("three", List.of()),
            "examiner-cap",
            copying(n -> modifyFixture("builders/edits-locked-test/attempt-" + n)),
            config.examiner().attempts());

    assertThat(a.path())
        .as(String.valueOf(a.github().comments(a.issue())))
        .isEqualTo(
            "SEALING > SEALED > BUILDING > MEASURING > REWORK(CONTRACT_VIOLATION)"
                + " > BUILDING > MEASURING > GRADING > PR_OPEN");
    assertThat(readFile(a.attempt(1).resolve("FEEDBACK.md")))
        .contains(
            "You modified `"
                + COUNTER_TEST_PATH
                + "`. This spec allows changes only to: `"
                + SealedItemFixture.COUNTER_PATH
                + "`, and new files. Restore those files and make the change another way.");
    assertThat(a.builder().requests()).hasSize(2);
    var branch = "noir/" + a.issue() + "-counter";
    assertThat(originFile(a.origin(), branch, COUNTER_TEST_PATH))
        .isEqualTo(readFile(modifyFixture("base").resolve(COUNTER_TEST_PATH)));
    assertThat(a.github().pullRequests())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.draft()).isFalse();
          assertThat(pr.body())
              .contains(
                  "### Files\n\n"
                      + "- Added: `ATTEMPT.txt` (+1)\n"
                      + "- Added: `src/test/java/com/example/api/CounterCapTest.java` (+24)\n"
                      + "- Modified: `"
                      + SealedItemFixture.COUNTER_PATH
                      + "` (+9 \u22122)\n")
              .doesNotContain("Tests updated:");
        });
  }

  @Test
  void specConflictingWithLockedTestIsRejected() {
    var a =
        approveModify(
            modifyBody("one", List.of()),
            "examiner-conflict",
            (n, work) -> {
              throw new AssertionError("building not expected");
            },
            1);

    assertThat(a.path())
        .as(String.valueOf(a.github().comments(a.issue())))
        .isEqualTo("SEALING > DRAFT");
    assertThat(a.github().comments(a.issue()))
        .containsExactly(
            ApprovalComments.conflictsWithExistingTests(List.of("com.example.api.CounterTest")));
    assertThat(a.store().readEvents(a.itemId()))
        .filteredOn(e -> e.type().equals("seal_rejected"))
        .singleElement()
        .satisfies(
            e -> assertThat(e.data()).containsEntry("reason", "conflicts_with_existing_tests"));
    assertThat(a.github().labels(a.issue()))
        .doesNotContain(NoirLabels.READY)
        .contains("noir:draft");
    assertThat(a.examiner().requests()).hasSize(1);
    assertThat(a.builder().requests()).isEmpty();
    assertThat(a.github().pullRequests()).isEmpty();
  }
}
