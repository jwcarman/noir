package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.AgentResult;
import org.jwcarman.noir.build.BuildOutcome;
import org.jwcarman.noir.build.BuildRunner;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.FakeBranchPublisher;
import org.jwcarman.noir.deliver.PatchTooLargeException;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.feedback.FeedbackInput;
import org.jwcarman.noir.feedback.FeedbackRenderer;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.grade.GradeArtifacts;
import org.jwcarman.noir.grade.GradeRunner;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.lifecycle.BlockReason;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.measure.MeasureArtifacts;
import org.jwcarman.noir.measure.MeasureJudge;
import org.jwcarman.noir.measure.MeasureOutcome;
import org.jwcarman.noir.measure.MeasureRunner;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class FactoryTest {
  static final String REPO = "jwcarman/demo";
  static final String SECRET = "sk-ant-secret-value";
  static final Path FIXTURES = Path.of("src/test/resources/fixtures");
  static final String COUNTER_STUB = "src/main/java/com/example/api/Counter.java";

  @TempDir Path home;
  Store store;
  Path itemDir;
  Spec spec;
  final FakeGitHub gh = new FakeGitHub();
  final FakeBranchPublisher publisher = new FakeBranchPublisher();
  final RecordingDelivery delivery = new RecordingDelivery(new GitHubDelivery(gh, publisher));
  final FakeBuild build = new FakeBuild();
  final FakeMeasure measure = new FakeMeasure();
  final FakeGrade grade = new FakeGrade();
  final FakeBundles bundles = new FakeBundles();
  final List<Duration> sleeps = new ArrayList<>();

  @BeforeEach
  void sealedItem() throws IOException {
    store = new Store(new NoirHome(home));
    itemDir = store.itemDir(Specs.ID);
    spec = Specs.valid().withApiStubs(List.of(COUNTER_STUB));
    copyTree(FIXTURES.resolve("seal/stubs"), itemDir.resolve("stubs"));
    copyTree(FIXTURES.resolve("seal/holdout-good"), itemDir.resolve("holdout"));
    SpecJson.write(spec, itemDir.resolve("spec.json"));
    Commitments.write(
        Commitments.create(spec, itemDir, "examiner-model"), itemDir.resolve("commitment.json"));
    Files.writeString(itemDir.resolve("base.bundle"), "# v2 git bundle\nbase\n");
    int issue = gh.createIssue(REPO, spec.title(), "body", List.of("noir:ready"));
    store.writeWorkItem(WorkItem.sealed(Specs.ID, REPO, issue));
  }

  Factory factory() {
    return factory(Set.of(SECRET));
  }

  Factory factory(Set<String> secrets) {
    return new Factory(
        store,
        delivery,
        build,
        measure,
        grade,
        bundles,
        NoirConfig.defaults(),
        "sha256:grader-image-id",
        Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC),
        new Retry(6, Duration.ofSeconds(2), Duration.ofSeconds(60), sleeps::add),
        secrets);
  }

  WorkItem drive() {
    factory().drive(Specs.ID);
    return store.readWorkItem(Specs.ID);
  }

  List<String> eventTypes() {
    return store.readEvents(Specs.ID).stream().map(Event::type).toList();
  }

  AttemptPaths attempt(int n) {
    return new AttemptPaths(itemDir, n);
  }

  @Test
  void happyPathOpensOnePullRequest() {
    var first = factory().advance(Specs.ID);
    assertThat(first.state()).isEqualTo(State.BUILDING);
    assertThat(first.attempt()).isEqualTo(1);
    assertThat(build.calls).isEmpty();

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(delivery.labels).containsExactly("noir:building", "noir:pr-open");
    assertThat(gh.issue(1).labels()).containsExactly("noir:pr-open");
    assertThat(gh.pullRequests()).hasSize(1);
    var pr = gh.pullRequests().get(0);
    assertThat(pr.draft()).isFalse();
    assertThat(pr.head()).isEqualTo("noir/1-add-a-bounded-cache");
    assertThat(pr.body())
        .contains("Closes #1.")
        .contains("R1: 3/3 passed")
        .contains("Builder model: claude-test")
        .contains("Examiner model: examiner-model")
        .contains("Grader image: `sha256:grader-image-id`")
        .doesNotContain("noir-grader:")
        .contains(
            "`" + Commitments.sha256(Commitments.read(itemDir.resolve("commitment.json"))) + "`");
    assertThat(publisher.pushes())
        .containsExactly(
            new FakeBranchPublisher.Push(REPO, attempt(1).bundle(), "noir/1-add-a-bounded-cache"));
    assertThat(build.calls.get(0).inputBundle()).isEqualTo(itemDir.resolve("base.bundle"));
    assertThat(build.calls.get(0).attemptDir()).isEqualTo(attempt(1).build());
    assertThat(eventTypes())
        .containsExactly(
            "transition",
            "build_attempt",
            "transition",
            "measurement",
            "transition",
            "grade",
            "delivered",
            "transition");
    var transitions =
        store.readEvents(Specs.ID).stream().filter(e -> e.type().equals("transition")).toList();
    assertThat(transitions.get(3).data())
        .containsEntry("from", "GRADING")
        .containsEntry("to", "PR_OPEN")
        .containsEntry("trigger", "GradePassed");
    var buildEvent = store.readEvents(Specs.ID).get(1).data();
    assertThat(buildEvent)
        .containsEntry("model", "claude-test")
        .containsEntry("turns", 10)
        .containsEntry("overBudget", false)
        .containsEntry("imageId", "sha256:builder");
    assertThat(sleeps).isEmpty();
  }

  @Test
  void measurementShortfallFeedsTheNextAttempt() throws IOException {
    measure.modes = n -> n == 1 ? MeasureMode.SHORTFALL : MeasureMode.PASS;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(item.attempt()).isEqualTo(2);
    var shortfall =
        (MeasureOutcome.Shortfall)
            MeasureJudge.judge(
                new MeasureArtifacts(attempt(1).measure()),
                spec.qualityBar(),
                List.of("com.example.Calc"));
    assertThat(shortfall.input()).isInstanceOf(FeedbackInput.QualityShortfall.class);
    var expected = FeedbackRenderer.render(1, shortfall.input());
    assertThat(Files.readString(attempt(1).feedback())).isEqualTo(expected);
    assertThat(build.calls).hasSize(2);
    assertThat(build.calls.get(0).feedback()).isEmpty();
    assertThat(build.calls.get(1).feedback()).contains(expected);
    assertThat(build.calls.get(1).inputBundle()).isEqualTo(attempt(1).bundle());
    assertThat(delivery.labels)
        .containsExactly("noir:building", "noir:building", "noir:building", "noir:pr-open");
  }

  @Test
  void gradeFailingEveryAttemptEndsFailedWithADraftPullRequest() {
    grade.modes = n -> GradeMode.FAIL;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(item.attempt()).isEqualTo(4);
    assertThat(build.calls).hasSize(4);
    assertThat(gh.pullRequests()).hasSize(1);
    var pr = gh.pullRequests().get(0);
    assertThat(pr.draft()).isTrue();
    assertThat(pr.body())
        .startsWith("**noir could not satisfy every requirement within 4 attempts.**")
        .contains("R1: 0/3 passed");
    assertThat(publisher.pushes().get(0).bundle()).isEqualTo(attempt(4).bundle());
    assertThat(delivery.labels).last().isEqualTo("noir:failed");
    assertThat(gh.issue(1).comments()).isEmpty();
  }

  @Test
  void noCommitsInAnyAttemptEndsFailedWithAComment() throws IOException {
    build.produces = n -> false;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(build.calls).hasSize(4);
    assertThat(measure.calls).isZero();
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(publisher.pushes()).isEmpty();
    assertThat(gh.issue(1).comments())
        .singleElement()
        .asString()
        .contains("could not satisfy every requirement within 4 attempts");
    assertThat(Files.readString(attempt(1).feedback()))
        .isEqualTo(FeedbackRenderer.render(1, new FeedbackInput.NoOutput()));
    assertThat(build.calls.get(1).inputBundle()).isEqualTo(attempt(1).bundle());
    assertThat(delivery.labels).last().isEqualTo("noir:failed");
  }

  @Test
  void holdoutCompileDefectBlocks() {
    grade.modes = n -> GradeMode.DEFECT;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.HOLDOUT_DEFECT);
    assertThat(delivery.labels).containsExactly("noir:building", "noir:blocked");
    assertThat(gh.pullRequests()).isEmpty();
  }

  @Test
  void holdoutDefectDetailNeverReachesTheBuilder() throws IOException {
    grade.modes = n -> n == 1 ? GradeMode.FAIL : GradeMode.DEFECT;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.attempt()).isEqualTo(2);
    List<Path> feedbackFiles;
    try (var files = Files.walk(itemDir.resolve("attempts"))) {
      feedbackFiles = files.filter(p -> p.getFileName().toString().equals("FEEDBACK.md")).toList();
    }
    assertThat(feedbackFiles).isNotEmpty();
    for (var f : feedbackFiles) {
      assertThat(Files.readString(f)).doesNotContain(FakeGrade.DETAIL).doesNotContain("Counter");
    }
    assertThat(build.calls)
        .allSatisfy(c -> assertThat(c.feedback().orElse("")).doesNotContain(FakeGrade.DETAIL));
    assertThat(gh.issue(1).comments()).noneMatch(c -> c.contains(FakeGrade.DETAIL));
    var gradeEvent =
        store.readEvents(Specs.ID).stream()
            .filter(e -> e.type().equals("grade") && e.attempt() == 2)
            .findFirst()
            .orElseThrow();
    assertThat(gradeEvent.data()).containsEntry("result", "holdout_defect");
  }

  @Test
  void secretInThePatchBlocksAndPushesNothing() {
    bundles.patch = "+String key = \"" + SECRET + "\";\n";

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.SECRET_FOUND);
    assertThat(publisher.pushes()).isEmpty();
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(measure.calls).isZero();
    assertThat(delivery.labels).last().isEqualTo("noir:blocked");
    assertThat(store.readEvents(Specs.ID).toString()).doesNotContain(SECRET);
  }

  @Test
  void secretInABuilderReportedValueBlocksThePullRequest() {
    build.model = SECRET; // a forged model id would be printed in the PR body

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.SECRET_FOUND);
    assertThat(item.blockedPhase()).isEqualTo(State.GRADING);
    assertThat(publisher.pushes()).isEmpty();
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(gh.issue(1).comments()).isEmpty();
    assertThat(delivery.labels).containsExactly("noir:building", "noir:blocked");
  }

  @Test
  void secretInAnIssueCommentBlocksTheComment() {
    build.produces = n -> false;
    // The guard is text-agnostic: any secret value appearing in the outbound comment blocks it.
    factory(Set.of(SECRET, "No attempt produced commits")).drive(Specs.ID);
    var item = store.readWorkItem(Specs.ID);

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.SECRET_FOUND);
    assertThat(item.blockedPhase()).isEqualTo(State.BUILDING);
    assertThat(gh.issue(1).comments()).isEmpty();
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(delivery.labels).last().isEqualTo("noir:blocked");
  }

  @Test
  void blankPatchIsAnInfrastructureFailureNotAPass() {
    bundles.patch = "";

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.MEASURING);
    assertThat(measure.calls).isZero();
    assertThat(publisher.pushes()).isEmpty();
    assertThat(gh.pullRequests()).isEmpty();
  }

  @Test
  void patchTooLargeToScanBlocksWithoutRetrying() {
    bundles.tooLarge = true;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.MEASURING);
    assertThat(measure.calls).isZero();
    assertThat(publisher.pushes()).isEmpty();
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(sleeps).isEmpty();
  }

  @Test
  void persistentDockerFailureBlocksAsInfrastructure() {
    measure.modes = n -> MeasureMode.THROW;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.MEASURING);
    assertThat(measure.calls).isEqualTo(6);
    assertThat(sleeps)
        .containsExactly(
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16),
            Duration.ofSeconds(32));
    assertThat(delivery.labels).last().isEqualTo("noir:blocked");
    assertThat(eventTypes()).contains("infra_failure");
  }

  @Test
  void transientDockerFailureIsRetried() {
    measure.modes = n -> n <= 2 ? MeasureMode.THROW : MeasureMode.PASS;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(item.attempt()).isEqualTo(1);
    assertThat(measure.calls).isEqualTo(3);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
  }

  @Test
  void pullRequestCreationFailingOnceStillOpensExactlyOne() {
    gh.failNextCreatePullRequest();

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(gh.pullRequests()).hasSize(1);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
  }

  @Test
  void pullRequestCreationFailingPersistentlyBlocksFromGrading() {
    delivery.failPullRequests = true;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.GRADING);
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(delivery.labels).containsExactly("noir:building", "noir:blocked");
  }

  static AgentResult invalidKey() {
    return new AgentResult(
        1,
        true,
        false,
        BigDecimal.ZERO,
        1,
        "claude-test",
        "Invalid API key \u00b7 Fix external API key",
        "sha256:builder");
  }

  @Test
  void agentErrorThatConsumedNothingBlocksAsInfrastructure() {
    build.produces = n -> false;
    build.agent = n -> invalidKey();

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.BUILDING);
    assertThat(item.attempt()).isEqualTo(1);
    assertThat(build.calls).hasSize(6);
    assertThat(sleeps).hasSize(5);
    assertThat(gh.issue(1).comments()).isEmpty();
    assertThat(gh.pullRequests()).isEmpty();
    assertThat(delivery.labels).containsExactly("noir:building", "noir:blocked");
    assertThat(eventTypes()).contains("infra_failure").doesNotContain("build_attempt");
    assertThat(attempt(1).feedback()).doesNotExist();
  }

  @Test
  void agentErrorWithNoResultJsonBlocksAsInfrastructure() {
    build.produces = n -> false;
    // What ClaudeJson reports when the container printed no result object and exited non-zero.
    build.agent =
        n -> new AgentResult(1, true, false, BigDecimal.ZERO, 0, "default", "boom", "sha256:b");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(gh.issue(1).comments()).isEmpty();
  }

  @Test
  void transientAgentInfraErrorIsRetriedWithoutUsingAnAttempt() {
    build.produces = n -> n != 1;
    build.agent =
        n ->
            n == 1
                ? invalidKey()
                : new AgentResult(
                    0, false, false, new BigDecimal("1.25"), 10, "claude-test", "done", "sha");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(item.attempt()).isEqualTo(1);
    assertThat(build.calls).hasSize(2);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
  }

  @Test
  void agentThatDidWorkThenErroredIsStillABuilderOutcome() {
    build.produces = n -> false;
    build.agent =
        n ->
            new AgentResult(
                1, true, false, new BigDecimal("0.40"), 1, "claude-test", "API Error", "sha");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(build.calls).hasSize(4);
    assertThat(sleeps).isEmpty();
    assertThat(Files.exists(attempt(1).feedback())).isTrue();
  }

  @Test
  void agentKilledAfterCommittingWorkIsStillABuilderOutcome() {
    // An OOM- or self-killed builder reports no result JSON, but its commits are real work.
    build.produces = n -> true;
    build.agent = n -> new AgentResult(137, true, false, BigDecimal.ZERO, 0, "default", "", "sha");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(build.calls).hasSize(1);
    assertThat(sleeps).isEmpty();
    assertThat(eventTypes()).doesNotContain("infra_failure");
  }

  @Test
  void agentThatTimedOutWithNothingReportedIsStillABuilderOutcome() {
    build.produces = n -> false;
    build.agent = n -> new AgentResult(137, true, true, BigDecimal.ZERO, 0, "default", "", "sha");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void agentErrorAfterSeveralTurnsIsStillABuilderOutcome() {
    build.produces = n -> false;
    build.agent =
        n -> new AgentResult(1, true, false, BigDecimal.ZERO, 5, "claude-test", "error", "sha");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(sleeps).isEmpty();
  }

  // ---- fakes -------------------------------------------------------------------------------

  /** Records every label applied, in order, then delegates. */
  static final class RecordingDelivery implements Delivery {
    final Delivery delegate;
    final List<String> labels = new ArrayList<>();
    boolean failPullRequests;

    RecordingDelivery(Delivery delegate) {
      this.delegate = delegate;
    }

    @Override
    public int openPullRequest(
        WorkItem item, Spec spec, AttemptPaths attempt, String body, boolean draft) {
      if (failPullRequests) {
        throw new GitHubException("GitHub is down");
      }
      return delegate.openPullRequest(item, spec, attempt, body, draft);
    }

    @Override
    public void comment(WorkItem item, String body) {
      delegate.comment(item, body);
    }

    @Override
    public void label(WorkItem item, State state) {
      delegate.label(item, state);
      labels.add(state.label().orElse(""));
    }
  }

  record BuildCall(Path inputBundle, Optional<String> feedback, Path attemptDir) {}

  static final class FakeBuild implements BuildRunner {
    final List<BuildCall> calls = new ArrayList<>();
    IntFunction<Boolean> produces = n -> true;
    String model = "claude-test";
    IntFunction<AgentResult> agent = null;

    @Override
    public BuildOutcome run(
        Spec spec,
        List<StubSource> stubs,
        Path inputBundle,
        String branch,
        Optional<String> feedback,
        Path attemptDir) {
      calls.add(new BuildCall(inputBundle, feedback, attemptDir));
      var bundle = attemptDir.resolve("out.bundle");
      write(bundle, "# v2 git bundle\nattempt " + calls.size() + "\n");
      var result =
          this.agent != null
              ? this.agent.apply(calls.size())
              : new AgentResult(
                  0, false, false, new BigDecimal("1.25"), 10, model, "done", "sha256:builder");
      return new BuildOutcome(bundle, produces.apply(calls.size()), result);
    }
  }

  enum MeasureMode {
    PASS,
    SHORTFALL,
    THROW
  }

  /** Writes artifacts the real {@link MeasureJudge} accepts (from the measure fixtures). */
  static final class FakeMeasure implements MeasureRunner {
    int calls;
    IntFunction<MeasureMode> modes = n -> MeasureMode.PASS;

    @Override
    public MeasureArtifacts run(
        Spec spec, Path attemptBundle, String branch, Path stubsDir, Path outDir) {
      calls++;
      var mode = modes.apply(calls);
      if (mode == MeasureMode.THROW) {
        throw new DockerException("daemon unavailable");
      }
      var fix = FIXTURES.resolve("measure");
      for (var f : List.of("jacoco.xml", "javap-stub.txt", "javap-built.txt", "mutations.xml")) {
        copy(fix.resolve(f), outDir.resolve(f));
      }
      for (var step :
          List.of("build", "holdout-sources", "forbidden", "api", "changed", "deps", "pit")) {
        write(outDir.resolve(step + ".exit"), "0\n");
      }
      for (var f : List.of("generated.txt", "holdout-sources.txt", "forbidden.txt")) {
        write(outDir.resolve(f), "");
      }
      write(outDir.resolve("stubs.txt"), "com.example.api.Counter\n");
      // The fixture's Calc has 50% line coverage: below the spec's 80% bar.
      write(
          outDir.resolve("changed.txt"),
          mode == MeasureMode.SHORTFALL ? "src/main/java/com/example/Calc.java\n" : "");
      return new MeasureArtifacts(outDir);
    }
  }

  enum GradeMode {
    PASS,
    FAIL,
    DEFECT
  }

  /** Writes artifacts the real {@code GradeJudge} reads, covering every sealed holdout test. */
  static final class FakeGrade implements GradeRunner {
    static final String DETAIL =
        "CounterTest.java:12: error: cannot find symbol hiddenHoldoutHelperName";
    int calls;
    IntFunction<GradeMode> modes = n -> GradeMode.PASS;

    @Override
    public GradeArtifacts run(
        Path classesDir, Path depsDir, Path holdoutDir, String holdoutPackage, Path outDir) {
      calls++;
      var mode = modes.apply(calls);
      write(outDir.resolve("forbidden.exit"), "0");
      write(outDir.resolve("forbidden.txt"), "");
      if (mode == GradeMode.DEFECT) {
        write(outDir.resolve("holdout-compile.exit"), "1");
        write(outDir.resolve("holdout-compile.log"), DETAIL + "\n");
        return new GradeArtifacts(outDir);
      }
      write(outDir.resolve("holdout-compile.exit"), "0");
      var tests =
          HoldoutAnalyzer.analyze(
                  SpecJson.read(holdoutDir.resolveSibling("spec.json")),
                  holdoutDir,
                  Set.of("com.example.api"))
              .tagsByTest()
              .keySet();
      boolean pass = mode == GradeMode.PASS;
      int runs = pass ? 1 : 3;
      for (int r = 1; r <= runs; r++) {
        var xml = new StringBuilder("<testsuite>");
        for (var t : tests) {
          int hash = t.indexOf('#');
          xml.append("<testcase classname=\"")
              .append(t, 0, hash)
              .append("\" name=\"")
              .append(t.substring(hash + 1))
              .append("()\">")
              .append(pass ? "" : "<failure message=\"boom\"/>")
              .append("</testcase>");
        }
        write(outDir.resolve("run-" + r + "/TEST-junit-jupiter.xml"), xml + "</testsuite>");
        write(outDir.resolve("run-" + r + ".exit"), pass ? "0" : "1");
      }
      return new GradeArtifacts(outDir);
    }
  }

  static final class FakeBundles implements Bundles {
    String patch = "+class Counter {}\n";
    boolean tooLarge;

    @Override
    public List<String> commitsSince(Path bundle, String baseSha, String branch) {
      return Files.isRegularFile(bundle) ? List.of("c0ffee") : List.of();
    }

    @Override
    public String patchSince(Path bundle, String baseSha, String branch) {
      if (tooLarge) {
        throw new PatchTooLargeException("patch exceeds 256 MiB");
      }
      return patch;
    }
  }

  // ---- file helpers ------------------------------------------------------------------------

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void copy(Path from, Path to) {
    try {
      Files.createDirectories(to.getParent());
      Files.copy(from, to);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (var p : paths.sorted(Comparator.naturalOrder()).toList()) {
        var target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }
}
