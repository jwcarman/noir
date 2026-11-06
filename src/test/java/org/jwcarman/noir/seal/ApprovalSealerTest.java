package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.factory.InfraFailure;
import org.jwcarman.noir.factory.Retry;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.git.RepoMirror;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.github.NoirLabels;
import org.jwcarman.noir.lifecycle.SealRejection;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.IssueSpecException;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.spec.SpliceException;
import org.jwcarman.noir.spec.StubSplicer;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class ApprovalSealerTest {
  static final String ID = Specs.ID;
  static final String REPO = "jwcarman/demo";
  static final String SECRET = "sk-ant-secret-123";
  static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
  static final Instant APPROVED = Instant.parse("2026-11-02T00:00:00Z");
  static final Instant LATER = Instant.parse("2026-11-03T00:00:00Z");
  static final String GOOD_TEST =
      "package "
          + ItemId.holdoutPackage(ID)
          + ";\nimport org.junit.jupiter.api.*;\nclass T {\n"
          + "  @Test @Tag(\"R1\") void a() {}\n  @Test @Tag(\"R2\") void b() {}\n}\n";

  @TempDir Path tmp;
  NoirHome home;
  Store store;
  FakeGitHub github;
  GitCli gitCli;
  String referencePath = "src/main/java/com/example/api/Foo.java";
  String referenceSource = "package com.example.api;\npublic interface Foo {}\n";
  Path origin;
  List<String> clones;
  Deque<BiFunction<SealInputs, Path, SealArtifacts>> checkResults;
  List<String> inventoryCalls;
  List<Integer> inventoryCallsSeenByExaminer;
  RuntimeException inventoryFailure;
  ScriptedAgent agent;
  ApprovalSealer sealer;
  int issue;
  WorkItem item;

  @BeforeEach
  void setUp() {
    home = new NoirHome(tmp.resolve("home"));
    store = new Store(home);
    github = new FakeGitHub().withNoirLabels("jwcarman/demo");
    gitCli = new GitCli(new ProcessCommandRunner());
    origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), REPO);
    clones = new ArrayList<>();
    checkResults = new ArrayDeque<>();
    inventoryCalls = new ArrayList<>();
    inventoryCallsSeenByExaminer = new ArrayList<>();
    agent =
        new ScriptedAgent((n, work) -> {
          inventoryCallsSeenByExaminer.add(inventoryCalls.size());
          write(work.resolve("out/holdout/noir/holdout/T.java"), GOOD_TEST);
          write(work.resolve("out/reference/" + referencePath), referenceSource);
        });
    var fixture =
        SealArtifactsFixture.checks((inputs, out) ->
            checkResults.isEmpty()
                ? SealArtifactsFixture.good(out)
                : checkResults.pop().apply(inputs, out));
    SealCheckRunner checks =
        new SealCheckRunner() {
          @Override
          public SealArtifacts run(SealInputs inputs, Path out) {
            return fixture.run(inputs, out);
          }

          @Override
          public RepoInventoryFiles inventory(Path bundle, String baseSha, Path out) {
            inventoryCalls.add(baseSha + " " + bundle.getFileName() + " " + out);
            if (inventoryFailure != null) {
              throw inventoryFailure;
            }
            return fixture.inventory(bundle, baseSha, out);
          }
        };
    var clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
    var config = NoirConfig.defaults();
    var git = new LocalGit();
    sealer =
        new ApprovalSealer(
            store,
            github,
            new RepoMirror(git, home.repos()),
            git,
            new SuiteSealer(
                store,
                new Examiner(agent, config),
                checks,
                git,
                config,
                clock,
                Retry.forConfig(config, d -> {})),
            config,
            clock,
            Set.of(SECRET));
    issue = openIssue(Specs.valid().withTitle("Add a bounded cache"));
    item = WorkItem.approved(ID, REPO, issue);
  }

  /** GitCli, except clones come from the local fixture origin instead of {@code gh}. */
  final class LocalGit implements GitPort {
    @Override
    public void fetch(Path repoDir, String branch) {
      gitCli.fetch(repoDir, branch);
    }

    @Override
    public String revParse(Path repoDir, String ref) {
      return gitCli.revParse(repoDir, ref);
    }

    @Override
    public String originRepo(Path repoDir) {
      return gitCli.originRepo(repoDir);
    }

    @Override
    public void bundle(Path repoDir, String sha, Path out) {
      gitCli.bundle(repoDir, sha, out);
    }

    @Override
    public void cloneRepo(String repo, Path dir) {
      clones.add(repo);
      GitFixtures.cloneOf(origin, dir);
    }

    @Override
    public java.util.Optional<String> show(Path repoDir, String rev, String path) {
      return gitCli.show(repoDir, rev, path);
    }

    @Override
    public List<String> listFiles(Path repoDir, String rev, String pathPrefix) {
      return gitCli.listFiles(repoDir, rev, pathPrefix);
    }

    @Override
    public boolean exists(Path repoDir, String sha, String path) {
      return gitCli.exists(repoDir, sha, path);
    }

    @Override
    public String diffNoIndex(Path dir, String oldPath, String newPath) {
      return gitCli.diffNoIndex(dir, oldPath, newPath);
    }
  }

  static String bodyOf(Spec spec) {
    return SpecRenderer.issueBody(spec, Specs.stubsFor(spec));
  }

  int openIssue(Spec spec) {
    return openIssue(spec.title(), bodyOf(spec));
  }

  int openIssue(String title, String body) {
    int n = github.createIssue(REPO, title, body, List.of());
    github.createdAt(n, CREATED);
    github.addLabel(n, NoirLabels.READY, "alice", APPROVED);
    return n;
  }

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Path itemDir() {
    return store.itemDir(ID);
  }

  List<String> eventTypes() {
    return store.readEvents(ID).stream().map(Event::type).toList();
  }

  Event event(String type) {
    return store.readEvents(ID).stream()
        .filter(e -> e.type().equals(type))
        .findFirst()
        .orElseThrow();
  }

  String originHead() {
    return GitFixtures.git(origin, "rev-parse", "main").strip();
  }

  void assertRejected(SealOutcome outcome, SealRejection reason, String comment, int problems) {
    assertThat(outcome).isEqualTo(new SealOutcome.Rejected(reason, comment));
    var data = event("seal_rejected").data();
    assertThat(data)
        .containsEntry("reason", reason.eventValue())
        .containsEntry("problems", problems)
        .containsOnlyKeys("reason", "problems", "details");
    assertThat(data.get("details")).asList().hasSize(problems);
    assertThat(github.comments(issue)).as("the caller posts rejection comments").isEmpty();
    assertThat(eventTypes()).doesNotContain("sealed", "commitment_posted");
    for (var leftover :
        List.of(
            "spec.json",
            "stubs",
            "fragments",
            "base.bundle",
            "holdout",
            "reference",
            "commitment.json")) {
      assertThat(itemDir().resolve(leftover)).doesNotExist();
    }
  }

  @Test
  void cleanApprovalSealsAndPostsCommitment() {
    var outcome = sealer.seal(item);

    var commitment = Commitments.read(itemDir().resolve("commitment.json"));
    var sha = Commitments.sha256(commitment);
    var bodySha = Commitments.sha256Hex(IssueSpec.normalize(github.issue(issue).body()));
    assertThat(outcome).isEqualTo(new SealOutcome.Passed(sha));
    assertThat(github.comments(issue))
        .containsExactly(
            "noir commitment `"
                + sha
                + "` (commitmentVersion 2, base `"
                + originHead()
                + "`, issue body sha256 `"
                + bodySha
                + "`)");
    assertThat(eventTypes())
        .containsExactly("approved", "examiner_attempt", "commitment_posted", "sealed");
    assertThat(event("approved").data())
        .isEqualTo(
            Map.of(
                "approver",
                "alice",
                "approvedAt",
                APPROVED.toString(),
                "issueCreatedAt",
                CREATED.toString(),
                "bodySha256",
                bodySha,
                "title",
                "Add a bounded cache",
                "sealedEditedAt",
                CREATED.toString()));
    assertThat(event("sealed").data())
        .isEqualTo(
            Map.of(
                "issue", issue,
                "commitmentSha256", sha,
                "baseSha", originHead(),
                "bodySha256", bodySha,
                "sealedEditedAt", CREATED.toString()));

    var spec = SpecJson.read(itemDir().resolve("spec.json"));
    assertThat(spec.baseSha()).isEqualTo(originHead());
    assertThat(spec.id()).isEqualTo(ID);
    assertThat(spec.repo()).isEqualTo(REPO);
    assertThat(spec.title()).isEqualTo("Add a bounded cache");
    assertThat(commitment.baseSha()).isEqualTo(originHead());
    assertThat(commitment.issueBodySha256()).isEqualTo(bodySha);
    assertThat(commitment.examinerModel()).isEqualTo("scripted");
    assertThat(itemDir().resolve("base.bundle")).exists();
    assertThat(itemDir().resolve("holdout/noir/holdout/T.java")).exists();
    assertThat(itemDir().resolve("stubs/src/main/java/com/example/api/Foo.java"))
        .hasContent(Specs.stubsFor(Specs.valid()).getFirst().content());
    assertThat(home.repos().resolve("jwcarman/demo/.git")).isDirectory();
    assertThat(clones).containsExactly(REPO);
  }

  @Test
  void inventoryIsListedOnceFromTheBaseBeforeTheExaminer() {
    assertThat(sealer.seal(item)).isInstanceOf(SealOutcome.Passed.class);

    assertThat(inventoryCallsSeenByExaminer).containsExactly(1);

    assertThat(inventoryCalls)
        .containsExactly(originHead() + " base.bundle " + itemDir().resolve("inventory"));
    assertThat(itemDir().resolve("inventory/libraries.txt")).exists();
    assertThat(itemDir().resolve("inventory/packages.txt")).exists();
  }

  @Test
  void failedInventoryIsAnInfrastructureFailure() {
    inventoryFailure = new DockerException("no network");

    assertThatThrownBy(() -> sealer.seal(item)).isInstanceOf(InfraFailure.class);

    assertThat(eventTypes()).containsExactly("approved");
    assertThat(itemDir().resolve("commitment.json")).doesNotExist();
    assertThat(inventoryCalls).hasSize(NoirConfig.defaults().infraRetries() + 1);
  }

  @Test
  void secondSealReusesTheMirror() {
    sealer.seal(item);
    int other = openIssue(Specs.valid());
    var second = WorkItem.approved("nr-01JBX4ZQ8K3V0M7N2P5R6S9T1X", REPO, other);
    // A different item id means a different holdout package, so this examination fails; only
    // the mirror reuse matters here.
    assertThat(sealer.seal(second)).isInstanceOf(SealOutcome.Rejected.class);
    assertThat(clones).containsExactly(REPO);
  }

  @Test
  void editAfterApprovalRejects() {
    github.editIssue(issue, "Add a bounded cache", bodyOf(Specs.valid()) + "more\n", LATER);
    var outcome = sealer.seal(item);
    assertRejected(
        outcome, SealRejection.EDITED_AFTER_APPROVAL, ApprovalComments.editedAfterApproval(), 1);
    assertThat(eventTypes()).containsExactly("seal_rejected");
    assertThat(agent.requests()).isEmpty();
    assertThat(clones).as("the lock is checked before touching the repository").isEmpty();
  }

  @Test
  void titleRenameAfterApprovalRejects() {
    github.editIssue(issue, "A renamed title", bodyOf(Specs.valid()), LATER);
    assertRejected(
        sealer.seal(item),
        SealRejection.EDITED_AFTER_APPROVAL,
        ApprovalComments.editedAfterApproval(),
        1);
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void editAtTheApprovalInstantIsNotAfterIt() {
    github.editIssue(issue, "Add a bounded cache", bodyOf(Specs.valid()), APPROVED);
    assertThat(sealer.seal(item)).isInstanceOf(SealOutcome.Passed.class);
  }

  @Test
  void missingApprovalRejects() {
    github.removeLabel(REPO, issue, NoirLabels.READY);
    assertRejected(
        sealer.seal(item),
        SealRejection.EDITED_AFTER_APPROVAL,
        ApprovalComments.editedAfterApproval(),
        1);
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void invalidBodyRejectsWithEveryProblem() {
    var b = bodyOf(Specs.valid());
    var broken =
        b.replace("## Constraints", "## Notes " + SECRET).replace("## Quality bar", "## Extra");
    issue = openIssue("Add a bounded cache", broken);
    item = WorkItem.approved(ID, REPO, issue);
    var expected =
        catchThrowableOfType(
                IssueSpecException.class, () -> IssueSpec.parse("Add a bounded cache", broken))
            .problems();
    assertThat(expected).hasSizeGreaterThan(1);

    var outcome = (SealOutcome.Rejected) sealer.seal(item);

    var masked = expected.stream().map(p -> p.replace(SECRET, "[redacted]")).toList();
    assertRejected(
        outcome, SealRejection.INVALID_SPEC, ApprovalComments.invalidSpec(masked), expected.size());
    assertThat(outcome.comment()).doesNotContain(SECRET);
    for (var p : masked) {
      assertThat(outcome.comment()).contains("- " + p + "\n");
    }
    assertThat(eventTypes()).containsExactly("approved", "seal_rejected");
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void lintProblemsReject() {
    issue = openIssue(Specs.valid().withIntent("TODO: explain"));
    item = WorkItem.approved(ID, REPO, issue);
    var outcome = (SealOutcome.Rejected) sealer.seal(item);
    assertThat(outcome.reason()).isEqualTo(SealRejection.INVALID_SPEC);
    assertThat(outcome.comment()).contains("- intent still contains a TODO placeholder\n");
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void existingStubPathRejects() {
    var path = "src/main/java/com/example/api/Placeholder.java";
    issue = openIssue(Specs.valid().withApiStubs(List.of(path)));
    item = WorkItem.approved(ID, REPO, issue);
    assertRejected(
        sealer.seal(item),
        SealRejection.STUB_EXISTS,
        ApprovalComments.stubExists(List.of(path)),
        1);
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void weakSuiteCommentHasNoTestNames() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out, "nr.Foo", "bar"));
    }
    var outcome = (SealOutcome.Rejected) sealer.seal(item);

    assertThat(outcome.reason()).isEqualTo(SealRejection.SUITE_TOO_WEAK);
    assertThat(outcome.comment())
        .isEqualTo(
            ApprovalComments.suiteTooWeak(3, List.of("1 hidden test passed against the stubs")))
        .contains("- 1 hidden test passed against the stubs\n")
        .doesNotContain("Foo")
        .doesNotContain("bar")
        .doesNotContain("nr.")
        .doesNotContain("#")
        .doesNotContain("vacuous")
        .doesNotContain("holdout")
        .doesNotContain("@Test")
        .doesNotContain("@Tag")
        .doesNotContain("package ")
        .doesNotContain(".java");
    assertRejected(outcome, SealRejection.SUITE_TOO_WEAK, outcome.comment(), 1);
    assertThat(agent.requests()).hasSize(3);
  }

  @Test
  void conflictRejectsWithReasonAndComment() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) ->
          SealArtifactsFixture.existingTestsFail(
              out,
              "com.example.api.LegacyCounterTest#incrementAddsTwo",
              "com.example.api.AuditTest#logsIncrement"));
    }
    var outcome = (SealOutcome.Rejected) sealer.seal(item);

    var expected =
        ApprovalComments.conflictsWithExistingTests(
            List.of("com.example.api.AuditTest", "com.example.api.LegacyCounterTest"));
    assertRejected(outcome, SealRejection.CONFLICTS_WITH_EXISTING_TESTS, expected, 2);
    assertThat(event("seal_rejected").data())
        .containsEntry("reason", "conflicts_with_existing_tests");
    assertThat(outcome.comment())
        .doesNotContain("incrementAddsTwo")
        .doesNotContain("logsIncrement")
        .doesNotContain("noir.holdout");
    assertThat(agent.requests()).hasSize(1);
  }

  @Test
  void rejectionCleansPartialFiles() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    }
    var outcome = (SealOutcome.Rejected) sealer.seal(item);
    var expected =
        ApprovalComments.suiteTooWeak(3, List.of("1 hidden test passed against the stubs"));
    assertThat(outcome.reason()).isEqualTo(SealRejection.SUITE_TOO_WEAK);
    assertThat(outcome.comment()).isEqualTo(expected);
    assertRejected(outcome, SealRejection.SUITE_TOO_WEAK, expected, 1);
    // The public comment only summarizes; the server's own event keeps the full problems.
    assertThat(event("seal_rejected").data().get("details"))
        .asList()
        .singleElement()
        .asString()
        .startsWith("holdout test passes against the stubs (vacuous): ");
    assertThat(itemDir().resolve("examiner/attempt-1")).isDirectory();
    assertThat(itemDir().resolve("examiner/attempt-3")).isDirectory();
    assertThat(itemDir().resolve("events.jsonl")).exists();
  }

  @Test
  void rejectionRemovesLeftoversFromAnInterruptedRun() {
    write(itemDir().resolve("spec.json"), "{}");
    write(itemDir().resolve("stubs/src/main/java/Old.java"), "class Old {}");
    write(itemDir().resolve("base.bundle"), "x");
    write(itemDir().resolve("holdout/T.java"), "x");
    write(itemDir().resolve("reference/R.java"), "x");
    write(itemDir().resolve("examiner/attempt-1/agent.log"), "history");
    store.writeWorkItem(item);
    github.editIssue(issue, "Add a bounded cache", bodyOf(Specs.valid()), LATER);

    assertRejected(
        sealer.seal(item),
        SealRejection.EDITED_AFTER_APPROVAL,
        ApprovalComments.editedAfterApproval(),
        1);
    assertThat(itemDir().resolve("examiner/attempt-1/agent.log")).hasContent("history");
    assertThat(itemDir().resolve("workitem.json")).exists();
  }

  @Test
  void crashAfterCommitmentWrittenFinishesWithoutExamining() {
    github.failNextComment();
    assertThatThrownBy(() -> sealer.seal(item)).isInstanceOf(GitHubException.class);
    assertThat(itemDir().resolve("commitment.json")).exists();
    assertThat(agent.requests()).hasSize(1);
    // The suite is frozen once committed; a later edit does not reopen it.
    github.editIssue(issue, "Add a bounded cache", bodyOf(Specs.valid()), LATER);

    var outcome = sealer.seal(item);

    var sha = Commitments.sha256(Commitments.read(itemDir().resolve("commitment.json")));
    assertThat(outcome).isEqualTo(new SealOutcome.Passed(sha));
    assertThat(agent.requests()).as("examiner not called again").hasSize(1);
    assertThat(github.comments(issue)).singleElement().asString().contains(sha);
    assertThat(eventTypes())
        .containsExactly("approved", "examiner_attempt", "commitment_posted", "sealed");
    assertThat(event("sealed").data())
        .containsEntry("baseSha", originHead())
        .as("the edit time of the body that was sealed, not of the later edit")
        .containsEntry("sealedEditedAt", CREATED.toString());
  }

  @Test
  void crashAfterCommentDoesNotRepost() throws IOException {
    var first = sealer.seal(item);
    // Simulate a crash after commitment_posted was recorded but before sealed was.
    var events = itemDir().resolve("events.jsonl");
    var kept =
        Files.readAllLines(events).stream()
            .filter(l -> !l.contains("\"type\":\"sealed\""))
            .collect(Collectors.joining("\n", "", "\n"));
    Files.writeString(events, kept);

    var again = sealer.seal(item);

    assertThat(again).isEqualTo(first);
    assertThat(github.comments(issue)).hasSize(1);
    assertThat(agent.requests()).hasSize(1);
    assertThat(eventTypes())
        .containsExactly("approved", "examiner_attempt", "commitment_posted", "sealed");
  }

  @Test
  void crlfBodyHashesNormalized() {
    var lf = bodyOf(Specs.valid());
    issue = openIssue("Add a bounded cache", lf.replace("\n", "\r\n") + "  \r\n\r\n");
    item = WorkItem.approved(ID, REPO, issue);

    sealer.seal(item);

    var lfSha = Commitments.sha256Hex(IssueSpec.normalize(lf));
    assertThat(lfSha)
        .isNotEqualTo(Commitments.sha256Hex(github.issue(issue).body()))
        .isEqualTo(Commitments.sha256Hex(IssueSpec.normalize(lf + "\n\n")));
    assertThat(event("approved").data()).containsEntry("bodySha256", lfSha);
    assertThat(event("sealed").data()).containsEntry("bodySha256", lfSha);
    assertThat(Commitments.read(itemDir().resolve("commitment.json")).issueBodySha256())
        .isEqualTo(lfSha);
  }

  @Test
  void commentTexts() {
    assertThat(ApprovalComments.editedAfterApproval())
        .isEqualTo(
            "noir did not seal this issue: it was edited after `noir:ready` was added. Please"
                + " review the current text and add `noir:ready` again.");
    assertThat(ApprovalComments.invalidSpec(List.of("a", "b")))
        .isEqualTo(
            "noir could not read this issue as a spec:\n\n- a\n- b\n\nFix the issue body (`noir"
                + " spec check --issue <repo>#<n> --repo <clone>` shows the same list), then add"
                + " `noir:ready` again.");
    assertThat(ApprovalComments.stubExists(List.of("x/A.java")))
        .isEqualTo(
            "noir did not seal this issue: these API stub files already exist on the default"
                + " branch:\n\n- x/A.java\n\nStubs may only add new files; change an existing"
                + " file with a `(modify)` entry. Revise the spec, then add `noir:ready` again.");
    assertThat(ApprovalComments.suiteTooWeak(3, List.of("p", "q")))
        .isEqualTo(
            "noir could not produce a hidden test suite that passes its checks after 3"
                + " attempts:\n\n- p\n- q\n\nThis usually means a requirement is ambiguous or"
                + " untestable as written. Revise the spec, then add `noir:ready` again.");
    assertThat(ApprovalComments.conflictsWithExistingTests(List.of("A", "B")))
        .isEqualTo(
            "noir did not seal this issue: the requested change breaks existing tests that this"
                + " spec does not allow to change: `A`, `B`. If those expectations should change,"
                + " list their files under `## Tests to update` and add `noir:ready` again.");
    assertThat(ApprovalComments.sealed("c", "b", "s"))
        .isEqualTo("noir commitment `c` (commitmentVersion 2, base `b`, issue body sha256 `s`)");
  }

  static final String CSV = "src/main/java/com/example/api/Csv.java";
  static final String CSV_BASE =
      "package com.example.api;\n\n/** Splits CSV lines. */\npublic final class Csv {\n"
          + "  /** Splits a line on commas. */\n"
          + "  public static String[] split(String line) {\n    return line.split(\",\");\n  }\n}\n";
  static final String CSV_FRAGMENT =
      "package com.example.api;\n\npublic final class Csv {\n"
          + "  /**\n   * Splits a line on a delimiter.\n   *\n   * @param line the line\n"
          + "   * @param delimiter the delimiter\n   * @return the fields\n   */\n"
          + "  public static String[] split(String line, char delimiter) {\n"
          + "    throw new UnsupportedOperationException();\n  }\n}\n";

  /** Commits {@code content} at {@code path} on the origin's {@code main}. */
  void commitToOrigin(String path, String content) {
    var work = tmp.resolve("origin-work-" + clones.size() + "-" + path.hashCode());
    GitFixtures.cloneOf(origin, work);
    write(work.resolve(path), content);
    GitFixtures.git(work, "add", ".");
    GitFixtures.git(work, "commit", "-q", "-m", "add " + path);
    GitFixtures.git(work, "push", "-q", "origin", "main");
  }

  Spec modifySpec() {
    return Specs.valid().withApiStubs(List.of(CSV)).withModifies(List.of(CSV)).withSchemaVersion(2);
  }

  void openModifyIssue(Spec spec, String fragment) {
    issue =
        openIssue(
            spec.title(), SpecRenderer.issueBody(spec, List.of(new StubSource(CSV, fragment))));
    item = WorkItem.approved(ID, REPO, issue);
  }

  @Test
  void modifySpecSplicesAgainstBaseAndStoresBoth() {
    commitToOrigin(CSV, CSV_BASE);
    openModifyIssue(modifySpec(), CSV_FRAGMENT);
    referencePath = CSV;
    referenceSource =
        CSV_BASE.replace(
            "  }\n}\n",
            "  }\n\n  public static String[] split(String line, char delimiter) {\n"
                + "    return line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)));"
                + "\n  }\n}\n");

    var outcome = sealer.seal(item);

    assertThat(outcome).isInstanceOf(SealOutcome.Passed.class);
    // The suite sealer diffed the reference against the base content sealing read.
    assertThat(itemDir().resolve("examiner/attempt-1/changed-lines/1/base.java"))
        .hasContent(CSV_BASE);
    var spliced = StubSplicer.splice(CSV, CSV_BASE, CSV_FRAGMENT).content();
    assertThat(spliced).contains("line.split(\",\")").contains("char delimiter");
    assertThat(itemDir().resolve("stubs").resolve(CSV)).hasContent(spliced);
    assertThat(itemDir().resolve("fragments").resolve(CSV)).hasContent(CSV_FRAGMENT);
    assertThat(SpecJson.read(itemDir().resolve("spec.json")).modifies()).containsExactly(CSV);
    assertThat(github.comments(issue)).singleElement().asString().startsWith("noir commitment `");
    assertThat(eventTypes())
        .containsExactly("approved", "examiner_attempt", "commitment_posted", "sealed");
    var prompt = agent.requests().getFirst().prompt();
    assertThat(prompt)
        .containsSubsequence(
            "### " + CSV + " (modify)",
            "char delimiter",
            "## Current source",
            "### " + CSV,
            "line.split(\",\")");
  }

  @Test
  void listedTestSourcesReachTheExaminer() {
    commitToOrigin(CSV, CSV_BASE);
    var test = "src/test/java/com/example/api/CsvTest.java";
    var testSource = "package com.example.api;\n\nclass CsvTest {}\n";
    commitToOrigin(test, testSource);
    openModifyIssue(modifySpec().withTestsToUpdate(List.of(test)), CSV_FRAGMENT);
    referencePath = CSV;
    referenceSource = CSV_BASE;

    sealer.seal(item);

    assertThat(agent.requests().getFirst().prompt())
        .contains("### " + test + " (test to update)\n\n```java\n" + testSource + "```\n");
  }

  @Test
  void modifyTargetMissingAtBaseIsInvalidSpec() {
    openModifyIssue(modifySpec(), CSV_FRAGMENT);
    var problem = "API contract: `" + CSV + "` (modify) does not exist on the default branch";
    assertRejected(
        sealer.seal(item),
        SealRejection.INVALID_SPEC,
        ApprovalComments.invalidSpec(List.of(problem)),
        1);
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void spliceFailureIsInvalidSpec() {
    commitToOrigin(CSV, CSV_BASE);
    var fragment = CSV_FRAGMENT.replace("public final class Csv", "public interface Csv");
    openModifyIssue(modifySpec(), fragment);
    var message =
        catchThrowableOfType(
                SpliceException.class, () -> StubSplicer.splice(CSV, CSV_BASE, fragment))
            .getMessage();
    assertThat(message).startsWith(CSV + ": ");

    var outcome = (SealOutcome.Rejected) sealer.seal(item);

    assertRejected(
        outcome, SealRejection.INVALID_SPEC, ApprovalComments.invalidSpec(List.of(message)), 1);
    assertThat(outcome.comment()).contains("- " + message + "\n");
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void testToUpdateMissingAtBaseIsInvalidSpec() {
    commitToOrigin(CSV, CSV_BASE);
    var test = "src/test/java/com/example/api/CsvTest.java";
    openModifyIssue(modifySpec().withTestsToUpdate(List.of(test)), CSV_FRAGMENT);
    var problem = "Tests to update: `" + test + "` does not exist on the default branch";
    assertRejected(
        sealer.seal(item),
        SealRejection.INVALID_SPEC,
        ApprovalComments.invalidSpec(List.of(problem)),
        1);
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void newFileStubStillRejectedWhenItExists() {
    commitToOrigin(CSV, CSV_BASE);
    var placeholder = "src/main/java/com/example/api/Placeholder.java";
    var spec =
        Specs.valid()
            .withApiStubs(List.of(placeholder, CSV))
            .withModifies(List.of(CSV))
            .withSchemaVersion(2);
    issue =
        openIssue(
            spec.title(),
            SpecRenderer.issueBody(
                spec, List.of(Specs.stubsFor(spec).getFirst(), new StubSource(CSV, CSV_FRAGMENT))));
    item = WorkItem.approved(ID, REPO, issue);
    assertRejected(
        sealer.seal(item),
        SealRejection.STUB_EXISTS,
        ApprovalComments.stubExists(List.of(placeholder)),
        1);
    assertThat(agent.requests()).isEmpty();
  }
}
