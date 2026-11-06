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
import java.util.Map;
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
import org.jwcarman.noir.deliver.FileChange;
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
import org.jwcarman.noir.lifecycle.SealRejection;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.measure.MeasureArtifacts;
import org.jwcarman.noir.measure.MeasureJudge;
import org.jwcarman.noir.measure.MeasureOutcome;
import org.jwcarman.noir.measure.MeasureRunner;
import org.jwcarman.noir.measure.TestInventory;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.seal.ApprovalSealer;
import org.jwcarman.noir.seal.ApprovalSealing;
import org.jwcarman.noir.seal.SealOutcome;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.SpecProblems;
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
  final FakeGitHub gh = new FakeGitHub().withNoirLabels("jwcarman/demo");
  final FakeBranchPublisher publisher = new FakeBranchPublisher();
  final RecordingDelivery delivery = new RecordingDelivery(new GitHubDelivery(gh, publisher));
  final FakeBuild build = new FakeBuild();
  final FakeMeasure measure = new FakeMeasure();
  final FakeGrade grade = new FakeGrade();
  final FakeBundles bundles = new FakeBundles();
  final List<Duration> sleeps = new ArrayList<>();
  final FakeSealing sealing = new FakeSealing();
  final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void sealedItem() throws IOException {
    store = new Store(new NoirHome(home));
    itemDir = store.itemDir(Specs.ID);
    spec = Specs.valid().withApiStubs(List.of(COUNTER_STUB));
    copyTree(FIXTURES.resolve("seal/stubs"), itemDir.resolve("stubs"));
    copyTree(FIXTURES.resolve("seal/holdout-good"), itemDir.resolve("holdout"));
    SpecJson.write(spec, itemDir.resolve("spec.json"));
    Commitments.write(
        Commitments.create(spec, itemDir, "examiner-model", "body"),
        itemDir.resolve("commitment.json"));
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
        sealing,
        build,
        measure,
        grade,
        bundles,
        NoirConfig.defaults(),
        "sha256:grader-image-id",
        clock,
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

  /** Sealed with hidden tests off: no holdout, so grading is skipped and the PR says so. */
  @Test
  void withoutHiddenTestsGradingIsSkipped() throws IOException {
    deleteTree(itemDir.resolve("holdout"));
    Commitments.write(
        Commitments.create(spec, itemDir, null, "body"), itemDir.resolve("commitment.json"));

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(grade.calls).isZero();
    var gradeEvent =
        store.readEvents(Specs.ID).stream().filter(e -> e.type().equals("grade")).toList();
    assertThat(gradeEvent)
        .singleElement()
        .satisfies(e -> assertThat(e.data()).containsEntry("result", "skipped"));
    assertThat(gh.pullRequests().get(0).body())
        .contains(
            "### Hidden tests\n\nOff for this item (enable with the noir:hidden-tests label or"
                + " hiddenTests: true).\n")
        .doesNotContain("### Holdout results")
        .contains("Examiner model: none")
        .contains("- Cost: $1.25 (builder only; as reported by Claude Code)");
  }

  static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  @Test
  void happyPathOpensOnePullRequest() {
    bundles.changes =
        List.of(new FileChange(FileChange.Status.ADDED, "src/main/java/A.java", null, 5, 0));
    var first = factory().advance(Specs.ID);
    assertThat(first.state()).isEqualTo(State.BUILDING);
    assertThat(first.attempt()).isEqualTo(1);
    assertThat(build.calls).isEmpty();

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(delivery.labels).containsExactly("noir:building", "noir:pr-open");
    assertThat(gh.issue(1).labels()).containsExactlyInAnyOrder("noir:ready", "noir:pr-open");
    assertThat(gh.pullRequests()).hasSize(1);
    var pr = gh.pullRequests().get(0);
    assertThat(pr.draft()).isFalse();
    assertThat(pr.head()).isEqualTo("noir/1-add-a-bounded-cache");
    assertThat(pr.body())
        .contains("Closes #1.")
        .contains("### Files\n\n- Added: `src/main/java/A.java` (+5)\n")
        .contains("R1: 3/3 passed")
        .contains("Builder model: claude-test")
        .contains("Examiner model: examiner-model")
        .contains("Grader image: `sha256:grader-image-id`")
        .contains("- Cost: $1.25 (builder $1.25, Examiner unknown; as reported by Claude Code)")
        .doesNotContain("noir-grader:")
        .contains(
            "`" + Commitments.sha256(Commitments.read(itemDir.resolve("commitment.json"))) + "`");
    assertThat(publisher.pushes())
        .containsExactly(
            new FakeBranchPublisher.Push(REPO, attempt(1).bundle(), "noir/1-add-a-bounded-cache"));
    assertThat(build.calls.get(0).inputBundle()).isEqualTo(itemDir.resolve("base.bundle"));
    assertThat(build.calls.get(0).attemptDir()).isEqualTo(attempt(1).build());
    assertThat(build.calls.get(0).commitMessage()).isEqualTo(spec.title() + " (#1)");
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
    assertThat(build.calls.get(1).commitMessage()).isEqualTo(spec.title() + " (#1), attempt 2");
    assertThat(delivery.labels)
        .containsExactly("noir:building", "noir:building", "noir:building", "noir:pr-open");
  }

  static final String R1_REPORT =
      "{\"problems\":[{\"requirement\":\"R1\",\"explanation\":\"R1 contradicts a locked"
          + " test.\"}]}";

  @Test
  void builderIsToldTheItemHasHiddenTests() {
    drive();
    assertThat(build.hiddenTests).containsOnly(true); // the fixture commitment has a holdout
  }

  @Test
  void builderIsToldTheItemHasNoHiddenTests() {
    Commitments.write(
        Commitments.create(spec, itemDir.resolve("no-such-dir"), null, "body"),
        itemDir.resolve("commitment.json"));
    drive();
    assertThat(build.hiddenTests).containsOnly(false);
  }

  @Test
  void builderReportStopsTheItem() {
    build.report = n -> R1_REPORT;
    build.produces = n -> false;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.DRAFT);
    assertThat(build.calls).hasSize(1);
    assertThat(gh.issue(1).labels()).containsExactly("noir:draft");
    assertThat(gh.comments(1))
        .containsExactly(
            org.jwcarman.noir.seal.SpecProblemComment.render(
                "builder",
                new SpecProblems(
                    List.of(
                        new SpecProblems.Problem(
                            "R1", List.of(), "R1 contradicts a locked test.")))));
    assertThat(eventTypes()).containsSubsequence("build_attempt", "spec_problems", "transition");
    assertThat(gh.pullRequests()).isEmpty();
  }

  @Test
  void builderReportWinsOverCommits() {
    build.report = n -> R1_REPORT;

    assertThat(drive().state()).isEqualTo(State.DRAFT);
    assertThat(measure.calls).isZero();
    assertThat(gh.pullRequests()).isEmpty();
  }

  @Test
  void invalidBuilderReportIsIgnored() {
    build.report =
        n -> n == 1 ? "{\"problems\":[{\"requirement\":\"R9\",\"explanation\":\"x\"}]}" : null;

    assertThat(drive().state()).isEqualTo(State.PR_OPEN);
    assertThat(eventTypes()).contains("spec_problems_invalid").doesNotContain("spec_problems");
    assertThat(
            store.readEvents(Specs.ID).stream()
                .filter(e -> e.type().equals("spec_problems_invalid"))
                .findFirst()
                .orElseThrow()
                .data())
        .hasEntrySatisfying("reason", r -> assertThat((String) r).contains("R9"));
  }

  @Test
  void fileReferenceWithoutBaseFileListIsInvalid() {
    build.report =
        n -> n == 1 ? "{\"problems\":[{\"files\":[\"pom.xml\"],\"explanation\":\"x\"}]}" : null;

    assertThat(drive().state()).isEqualTo(State.PR_OPEN);
    assertThat(eventTypes()).contains("spec_problems_invalid");
  }

  @Test
  void fileReferenceIsCheckedAgainstTheBaseFileList() throws IOException {
    Files.writeString(itemDir.resolve(ApprovalSealer.BASE_FILES), "pom.xml\n");
    build.report = n -> "{\"problems\":[{\"files\":[\"pom.xml\"],\"explanation\":\"x\"}]}";

    assertThat(drive().state()).isEqualTo(State.DRAFT);
  }

  @Test
  void reportWithASecretIsMaskedAndPosted() {
    build.report =
        n -> "{\"problems\":[{\"requirement\":\"R1\",\"explanation\":\"key " + SECRET + "\"}]}";

    var item = drive();

    assertThat(item.state()).isEqualTo(State.DRAFT);
    assertThat(gh.comments(1)).singleElement().asString().contains("key ").doesNotContain(SECRET);
    assertThat(store.readEvents(Specs.ID).toString()).doesNotContain(SECRET);
  }

  @Test
  void aSecretCutByTheExplanationCapLeavesNoFragment() {
    var explanation = "y".repeat(SpecProblems.MAX_EXPLANATION - 10) + SECRET;
    build.report =
        n -> "{\"problems\":[{\"requirement\":\"R1\",\"explanation\":\"" + explanation + "\"}]}";

    drive();

    assertThat(gh.comments(1)).singleElement().asString().doesNotContain(SECRET.substring(0, 10));
  }

  @Test
  void aSecretCutByTheLastMessageCapLeavesNoFragment() {
    build.produces = n -> n > 1;
    var text = SECRET + "b".repeat(Factory.LAST_MESSAGE_CHARS - 10);
    build.agent =
        n -> new AgentResult(0, false, false, new BigDecimal("0.10"), 3, "m", text, "sha256:b");

    drive();

    assertThat((String) firstEvent("build_attempt", 1).data().get("lastMessage"))
        .doesNotContain(SECRET.substring(SECRET.length() - 10));
  }

  @Test
  void reapprovedItemStartsOverFromTheBase() {
    build.report = n -> n == 1 ? R1_REPORT : null;
    var drafted = drive();
    assertThat(drafted.state()).isEqualTo(State.DRAFT);

    Transitions.applyExternal(
        store, clock, (i, s) -> {}, drafted, new org.jwcarman.noir.lifecycle.Trigger.Approved());
    recommit(); // what the (faked) reseal writes
    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(item.attempt()).isEqualTo(1);
    assertThat(build.calls).hasSize(2);
    assertThat(build.calls.get(1).inputBundle()).isEqualTo(itemDir.resolve("base.bundle"));
    assertThat(build.calls.get(1).feedback()).isEmpty();
    assertThat(itemDir.resolve("runs/1/attempts/1")).isDirectory();
  }

  @Test
  void aRestartedItemsFailureIgnoresTheEarlierRun() {
    // Run 1: attempt 1 commits and falls short; attempt 2 reports a spec problem.
    measure.modes = n -> MeasureMode.SHORTFALL;
    build.report = n -> n == 2 ? R1_REPORT : null;
    var drafted = drive();
    assertThat(drafted.state()).isEqualTo(State.DRAFT);
    Transitions.applyExternal(
        store, clock, (i, s) -> {}, drafted, new org.jwcarman.noir.lifecycle.Trigger.Approved());
    recommit(); // what the (faked) reseal writes

    // Run 2: no attempt commits.
    build.report = n -> null;
    build.produces = n -> false;
    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(gh.pullRequests()).as("run 1's commits are not delivered").isEmpty();
    assertThat(gh.comments(1))
        .last()
        .asString()
        .contains("- Attempts 1\u20134: no commits")
        .doesNotContain("quality shortfall");
  }

  @Test
  void lockViolationIsContractViolationRework() throws IOException {
    bundles.changes = List.of(new FileChange(FileChange.Status.MODIFIED, "pom.xml", null, 1, 0));

    drive();

    assertThat(transitionsTo("REWORK").getFirst()).containsEntry("category", "CONTRACT_VIOLATION");
    assertThat(Files.readString(attempt(1).feedback()))
        .contains(
            "You modified `pom.xml`. This spec allows changes only to: new files. Restore those"
                + " files and make the change another way.");
    assertThat(measure.calls).isZero();
  }

  static String report(String... casesXml) {
    return "<testsuite>" + String.join("", casesXml) + "</testsuite>";
  }

  static final String ADDS_PASSED =
      "<testcase classname=\"com.example.CalcTest\" name=\"adds()\"/>";
  static final String ADDS_SKIPPED =
      "<testcase classname=\"com.example.CalcTest\" name=\"adds()\"><skipped/></testcase>";

  @Test
  void lockedTestThatStopsPassingIsContractViolationRework() throws IOException {
    TestInventory.write(
        List.of("com.example.CalcTest#adds()", "com.example.CalcTest#subtracts()"),
        itemDir.resolve("baseline-tests.json"));
    measure.testReports =
        n ->
            n == 1
                ? report(ADDS_SKIPPED)
                : report(
                    ADDS_PASSED,
                    "<testcase classname=\"com.example.CalcTest\" name=\"subtracts()\"/>");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(transitionsTo("REWORK").getFirst()).containsEntry("category", "CONTRACT_VIOLATION");
    assertThat(Files.readString(attempt(1).feedback()))
        .contains(
            "These existing tests passed on the base commit but did not pass on your branch"
                + " (skipped or not run): `com.example.CalcTest#adds()`,"
                + " `com.example.CalcTest#subtracts()`.");
  }

  static final String ADDS_FAILED =
      "<testcase classname=\"com.example.CalcTest\" name=\"adds()\"><failure message=\"no\"/>"
          + "</testcase>";

  @Test
  void lockedTestFailureIsRecordedAndFedBack() throws IOException {
    TestInventory.write(
        List.of("com.example.CalcTest#adds()"), itemDir.resolve("baseline-tests.json"));
    measure.modes = n -> n == 1 ? MeasureMode.BUILD_FAILED : MeasureMode.PASS;
    measure.testReports = n -> n == 1 ? report(ADDS_FAILED) : report(ADDS_PASSED);

    drive();

    var m = firstEvent("measurement", 1);
    assertThat(m.data()).containsEntry("lockedTestsFailed", List.of("com.example.CalcTest#adds()"));
    assertThat(Files.readString(attempt(1).feedback()))
        .contains("These failing tests are locked: `com.example.CalcTest#adds()`.");
  }

  @Test
  void noOutputRecordsTheBuildersLastMessage() {
    build.produces = n -> n > 1;
    var text =
        java.util.stream.IntStream.rangeClosed(1, 15)
            .mapToObj(i -> "line " + i)
            .collect(java.util.stream.Collectors.joining("\n"));
    build.agent =
        n ->
            new AgentResult(
                0, false, false, new BigDecimal("0.10"), 3, "m", text, "sha256:builder");

    drive();

    assertThat(firstEvent("build_attempt", 1).data().get("lastMessage"))
        .isEqualTo(
            java.util.stream.IntStream.rangeClosed(6, 15)
                .mapToObj(i -> "line " + i)
                .collect(java.util.stream.Collectors.joining("\n")));
    assertThat(firstEvent("build_attempt", 2).data()).doesNotContainKey("lastMessage");
  }

  @Test
  void contractViolationRecordsItsFirstMessage() {
    bundles.changes = List.of(new FileChange(FileChange.Status.MODIFIED, "pom.xml", null, 1, 0));

    drive();

    assertThat((String) firstEvent("measurement", 1).data().get("violation"))
        .startsWith("You modified `pom.xml`.");
  }

  @Test
  void failedItemExplainsItself() throws IOException {
    TestInventory.write(
        List.of("com.example.CalcTest#adds()"), itemDir.resolve("baseline-tests.json"));
    measure.modes = n -> MeasureMode.BUILD_FAILED;
    measure.testReports = n -> report(ADDS_FAILED);
    build.produces = n -> n == 1;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.FAILED);
    assertThat(gh.pullRequests())
        .singleElement()
        .satisfies(pr ->
            assertThat(pr.body())
                .contains("### Why it failed")
                .contains("- Attempt 1: the build failed.")
                .contains(
                    "- Attempts 2\u20134: no commits; the builder stopped without changing anything.")
                .contains("Existing tests that failed: `com.example.CalcTest#adds()`")
                .contains(
                    "list `src/test/java/com/example/CalcTest.java` under `## Tests to update`"));
  }

  @Test
  void failureCommentExplainsItselfToo() {
    build.produces = n -> false;

    drive();

    assertThat(gh.comments(1))
        .last()
        .asString()
        .contains("### Why it failed")
        .contains("- Attempts 1\u20134: no commits");
  }

  /** The commitment a reseal writes (the fake sealer writes none). */
  void recommit() {
    Commitments.write(
        Commitments.create(spec, itemDir, "examiner-model", "body"),
        itemDir.resolve("commitment.json"));
  }

  Event firstEvent(String type, int attempt) {
    return store.readEvents(Specs.ID).stream()
        .filter(e -> e.type().equals(type) && e.attempt() == attempt)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void testsInATestToUpdateMayStopRunning() throws IOException {
    spec = spec.withTestsToUpdate(List.of("src/test/java/com/example/CalcTest.java"));
    SpecJson.write(spec, itemDir.resolve("spec.json"));
    Commitments.write(
        Commitments.create(spec, itemDir, "examiner-model", "body"),
        itemDir.resolve("commitment.json"));
    TestInventory.write(
        List.of("com.example.CalcTest#adds()"), itemDir.resolve("baseline-tests.json"));

    assertThat(drive().state()).isEqualTo(State.PR_OPEN);
    assertThat(transitionsTo("REWORK")).isEmpty();
  }

  @Test
  void failedBuildIsABuildFailureNotAMissingTestViolation() throws IOException {
    TestInventory.write(
        List.of("com.example.CalcTest#adds()"), itemDir.resolve("baseline-tests.json"));
    measure.modes = n -> n == 1 ? MeasureMode.BUILD_FAILED : MeasureMode.PASS;
    measure.testReports = n -> n == 1 ? null : report(ADDS_PASSED);

    drive();

    assertThat(transitionsTo("REWORK").getFirst()).containsEntry("category", "BUILD_FAILED");
  }

  @Test
  void modifyTargetIsMeasuredOnlyOnItsChangedLines() throws IOException {
    var calc = "src/main/java/com/example/Calc.java";
    spec = spec.withModifies(List.of(calc));
    SpecJson.write(spec, itemDir.resolve("spec.json"));
    Commitments.write(
        Commitments.create(spec, itemDir, "examiner-model", "body"),
        itemDir.resolve("commitment.json"));
    bundles.changes = List.of(new FileChange(FileChange.Status.MODIFIED, calc, null, 1, 0));
    // Only line 5 (covered in the fixture) changes; the whole class is below the 80% bar.
    bundles.diff =
        "diff --git a/"
            + calc
            + " b/"
            + calc
            + "\nindex 1..2 100644\n--- a/"
            + calc
            + "\n+++ b/"
            + calc
            + "\n@@ -4,0 +5 @@\n+b\n";
    measure.modes = n -> MeasureMode.SHORTFALL;

    var item = drive();

    assertThat(item.state()).isEqualTo(State.PR_OPEN);
    assertThat(item.attempt()).isEqualTo(1);
    assertThat(bundles.diffPaths).containsExactly(calc);
  }

  @Test
  void changedLinesAreKeyedByClassName() {
    var patch =
        "diff --git a/src/main/java/a/b/C.java b/src/main/java/a/b/C.java\n"
            + "--- a/src/main/java/a/b/C.java\n+++ b/src/main/java/a/b/C.java\n"
            + "@@ -1 +1,2 @@\n x\n+y\n";
    assertThat(
            Factory.changedLinesByClass(
                patch, List.of("src/main/java/a/b/C.java", "src/main/java/a/D.java", "README.md")))
        .isEqualTo(Map.of("a.b.C", Set.of(2), "a.D", Set.of()));
  }

  @Test
  void measurementReworkTransitionCarriesItsCategory() {
    measure.modes = n -> n == 1 ? MeasureMode.SHORTFALL : MeasureMode.PASS;

    drive();

    assertThat(transitionsTo("REWORK").getFirst())
        .containsEntry("failure", "QUALITY_SHORTFALL")
        .containsEntry("category", "QUALITY_SHORTFALL");
  }

  @Test
  void gradeFailureTransitionCarriesBehaviorFailure() {
    grade.modes = n -> GradeMode.FAIL;

    drive();

    assertThat(transitionsTo("REWORK").getFirst())
        .containsEntry("failure", "BEHAVIOR_FAILURE")
        .containsEntry("category", "BEHAVIOR_FAILURE");
    assertThat(transitionsTo("FAILED").getLast()).containsEntry("category", "BEHAVIOR_FAILURE");
  }

  private List<java.util.Map<String, Object>> transitionsTo(String state) {
    return store.readEvents(Specs.ID).stream()
        .filter(e -> e.type().equals("transition") && state.equals(e.data().get("to")))
        .map(Event::data)
        .toList();
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

  // ---- sealing -----------------------------------------------------------------------------

  static final String REJECTION = "noir did not seal this issue: test rejection.";

  void sealingItem() {
    store.writeWorkItem(WorkItem.approved(Specs.ID, REPO, 1));
  }

  @Test
  void sealingPassedMovesToSealed() {
    sealingItem();

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.SEALED);
    assertThat(sealing.calls).isEqualTo(1);
    assertThat(gh.labels(1)).containsExactlyInAnyOrder("noir:ready", "noir:building");
    assertThat(eventTypes()).containsExactly("transition");
    assertThat(transitionsTo("SEALED").getFirst())
        .containsEntry("from", "SEALING")
        .containsEntry("trigger", "SealPassed");
    assertThat(build.calls).isEmpty();
  }

  void rejecting() {
    sealing.outcome = n -> new SealOutcome.Rejected(SealRejection.EDITED_AFTER_APPROVAL, REJECTION);
  }

  @Test
  void sealingRejectedCommentsRemovesReadyAndDrafts() throws IOException {
    sealingItem();
    rejecting();
    Files.delete(itemDir.resolve("spec.json")); // SEALING runs before spec.json exists

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.DRAFT);
    assertThat(gh.labels(1)).containsExactly("noir:draft");
    assertThat(gh.comments(1)).containsExactly(REJECTION);
    assertThat(delivery.calls).containsExactly("comment", "removeReady", "label noir:draft");
    assertThat(transitionsTo("DRAFT").getFirst())
        .containsEntry("from", "SEALING")
        .containsEntry("trigger", "SealRejected")
        .containsEntry("reason", "edited_after_approval");

    factory().drive(Specs.ID); // DRAFT waits for a human: nothing runs
    assertThat(store.readWorkItem(Specs.ID).state()).isEqualTo(State.DRAFT);
    assertThat(sealing.calls).isEqualTo(1);
    assertThat(build.calls).isEmpty();
  }

  @Test
  void conflictRejectionRemovesReadyAndDraftsWithReason() {
    sealingItem();
    sealing.outcome =
        n -> new SealOutcome.Rejected(SealRejection.CONFLICTS_WITH_EXISTING_TESTS, REJECTION);

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.DRAFT);
    assertThat(gh.labels(1)).containsExactly("noir:draft");
    assertThat(gh.comments(1)).containsExactly(REJECTION);
    assertThat(transitionsTo("DRAFT").getFirst())
        .containsEntry("reason", "conflicts_with_existing_tests");
  }

  @Test
  void rejectionCommentIsPostedOnceWhenRemovingReadyIsRetried() {
    sealingItem();
    rejecting();
    gh.failRemoveLabel(2);

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.DRAFT);
    assertThat(gh.comments(1)).containsExactly(REJECTION);
    assertThat(gh.labels(1)).containsExactly("noir:draft");
    assertThat(sleeps).hasSize(2);
  }

  @Test
  void sealingInfraFailureBlocks() {
    sealingItem();
    sealing.outcome =
        n -> {
          throw new GitHubException("GitHub is down");
        };

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.SEALING);
    assertThat(sealing.calls).isEqualTo(6);
    assertThat(sleeps).hasSize(5);
    assertThat(gh.labels(1)).containsExactlyInAnyOrder("noir:ready", "noir:blocked");
    assertThat(gh.comments(1)).containsExactly(BLOCKED_NOTICE);
    assertThat(store.readEvents(Specs.ID))
        .filteredOn(e -> e.type().equals("infra_failure"))
        .singleElement()
        .satisfies(e -> assertThat(e.data()).containsEntry("phase", "SEALING"));
  }

  @Test
  void sealerErrorBlocksAndItsMessageStaysLocal() {
    sealingItem();
    var hidden = "examiner output naming HiddenHoldoutTest.secretCase with " + SECRET;
    sealing.outcome =
        n -> {
          throw new IllegalStateException(hidden);
        };

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.SEALING);
    assertThat(sealing.calls).isEqualTo(1);
    assertThat(sleeps).isEmpty();
    assertThat(gh.comments(1)).containsExactly(BLOCKED_NOTICE);
    assertThat(gh.labels(1)).containsExactlyInAnyOrder("noir:ready", "noir:blocked");
    var error =
        (String)
            store.readEvents(Specs.ID).stream()
                .filter(e -> e.type().equals("infra_failure"))
                .findFirst()
                .orElseThrow()
                .data()
                .get("error");
    assertThat(error).contains("HiddenHoldoutTest").doesNotContain(SECRET);
  }

  static final String BLOCKED_NOTICE =
      "noir hit an infrastructure problem while sealing this issue. Nothing about the spec is"
          + " wrong. The operator can retry with `noir resume "
          + Specs.ID
          + "` on the noir server.";

  @Test
  void blockedWhileSealingPostsOneNoticePerBlock() {
    sealingItem();
    sealing.outcome =
        n -> {
          throw new IllegalStateException("examiner failed: " + SECRET);
        };

    var blocked = factory().advance(Specs.ID);
    factory().drive(Specs.ID); // BLOCKED: nothing runs, nothing is posted again

    assertThat(gh.comments(1)).containsExactly(BLOCKED_NOTICE);
    assertThat(eventTypes()).containsSubsequence("infra_failure", "transition", "blocked_notice");
    assertThat(String.join("\n", gh.comments(1))).doesNotContain("examiner", SECRET);

    Transitions.applyExternal(
        store, clock, delivery::label, blocked, new Trigger.ResumeRequested());
    factory().advance(Specs.ID);

    assertThat(gh.comments(1)).containsExactly(BLOCKED_NOTICE, BLOCKED_NOTICE);
    assertThat(eventTypes()).filteredOn(t -> t.equals("blocked_notice")).hasSize(2);
  }

  @Test
  void blockedNoticeIsGuardedAgainstSecrets() {
    sealingItem();
    sealing.outcome =
        n -> {
          throw new IllegalStateException("examiner failed");
        };

    var item = factory(Set.of(SECRET, "resume")).advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(gh.comments(1)).isEmpty();
    assertThat(eventTypes()).doesNotContain("blocked_notice");
  }

  @Test
  void blockedNoticeFailureStillBlocks() {
    sealingItem();
    sealing.outcome =
        n -> {
          throw new IllegalStateException("examiner failed");
        };
    gh.failNextComment();

    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(gh.comments(1)).hasSizeLessThanOrEqualTo(1);
  }

  @Test
  void blockedOutsideSealingPostsNoNotice() {
    // An agent that never reached the model is infrastructure: retried, then blocked.
    build.produces = n -> false;
    build.agent = n -> new AgentResult(1, true, false, null, 0, "m", "auth failed", "img");

    var item = drive();

    assertThat(item.state()).isEqualTo(State.BLOCKED);
    assertThat(item.blockReason()).isEqualTo(BlockReason.INFRASTRUCTURE);
    assertThat(item.blockedPhase()).isEqualTo(State.BUILDING);
    assertThat(gh.comments(1)).isEmpty();
    assertThat(eventTypes()).doesNotContain("blocked_notice");
  }

  @Test
  void resumeBlockedSealingReseals() {
    sealingItem();
    sealing.outcome =
        n -> {
          throw new GitHubException("GitHub is down");
        };
    var blocked = factory().advance(Specs.ID);
    sealing.outcome = n -> new SealOutcome.Passed("sha");

    var resumed =
        Transitions.applyExternal(
            store, clock, delivery::label, blocked, new Trigger.ResumeRequested());
    assertThat(resumed.to()).isEqualTo(State.SEALING);
    var item = factory().advance(Specs.ID);

    assertThat(item.state()).isEqualTo(State.SEALED);
    assertThat(sealing.calls).isEqualTo(7);
    assertThat(gh.labels(1)).containsExactlyInAnyOrder("noir:ready", "noir:building");
  }

  /** Scripted sealing: {@code outcome} gets the 1-based call number. */
  static final class FakeSealing implements ApprovalSealing {
    int calls;
    IntFunction<SealOutcome> outcome = n -> new SealOutcome.Passed("sha");

    @Override
    public SealOutcome seal(WorkItem item) {
      calls++;
      return outcome.apply(calls);
    }
  }

  // ---- fakes -------------------------------------------------------------------------------

  /** Records every label applied, in order, then delegates. */
  static final class RecordingDelivery implements Delivery {
    final Delivery delegate;
    final List<String> labels = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
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
      calls.add("comment");
    }

    @Override
    public void label(WorkItem item, State state) {
      delegate.label(item, state);
      labels.add(state.label().orElse(""));
      calls.add("label " + state.label().orElse(""));
    }

    @Override
    public void removeReady(WorkItem item) {
      delegate.removeReady(item);
      calls.add("removeReady");
    }
  }

  record BuildCall(
      Path inputBundle, String commitMessage, Optional<String> feedback, Path attemptDir) {}

  static final class FakeBuild implements BuildRunner {
    final List<BuildCall> calls = new ArrayList<>();
    IntFunction<Boolean> produces = n -> true;
    String model = "claude-test";
    IntFunction<AgentResult> agent = null;

    final List<Boolean> hiddenTests = new ArrayList<>();

    /** The SPEC-PROBLEMS.json each attempt writes, or null for none. */
    IntFunction<String> report = n -> null;

    @Override
    public BuildOutcome run(
        Spec spec,
        List<StubSource> stubs,
        Path inputBundle,
        String branch,
        String commitMessage,
        Optional<String> feedback,
        Path attemptDir,
        boolean hiddenTests) {
      this.hiddenTests.add(hiddenTests);
      calls.add(new BuildCall(inputBundle, commitMessage, feedback, attemptDir));
      var problems = report.apply(calls.size());
      if (problems != null) {
        write(attemptDir.resolve("SPEC-PROBLEMS.json"), problems);
      }
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
    BUILD_FAILED,
    THROW
  }

  /** Writes artifacts the real {@link MeasureJudge} accepts (from the measure fixtures). */
  static final class FakeMeasure implements MeasureRunner {
    int calls;
    IntFunction<MeasureMode> modes = n -> MeasureMode.PASS;

    /** The build's JUnit report for each attempt, or null for none. */
    IntFunction<String> testReports = n -> null;

    @Override
    public MeasureArtifacts run(
        Spec spec, Path attemptBundle, String branch, Path stubsDir, Path outDir) {
      calls++;
      var report = testReports.apply(calls);
      if (report != null) {
        write(outDir.resolve("test-reports/surefire-reports-TEST-x.xml"), report);
      }
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
      if (mode == MeasureMode.BUILD_FAILED) {
        write(outDir.resolve("build.exit"), "1\n");
        write(outDir.resolve("build.log"), "[ERROR] BUILD FAILURE\n");
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
    List<FileChange> changes = List.of();

    @Override
    public List<String> commitsSince(Path bundle, String baseSha, String branch) {
      return Files.isRegularFile(bundle) ? List.of("c0ffee") : List.of();
    }

    @Override
    public List<FileChange> fileChanges(Path bundle, String baseSha, String branch) {
      return changes;
    }

    @Override
    public String tip(Path bundle, String branch) {
      return "c0ffee";
    }

    String diff = "";
    List<String> diffPaths;

    @Override
    public String diffSince(Path bundle, String baseSha, String branch, List<String> paths) {
      diffPaths = paths;
      return diff;
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
