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
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.git.RepoMirror;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.github.NoirLabels;
import org.jwcarman.noir.lifecycle.SealRejection;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.IssueSpecException;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
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
  Path origin;
  List<String> clones;
  Deque<BiFunction<SealInputs, Path, SealArtifacts>> checkResults;
  ScriptedAgent agent;
  ApprovalSealer sealer;
  int issue;
  WorkItem item;

  @BeforeEach
  void setUp() {
    home = new NoirHome(tmp.resolve("home"));
    store = new Store(home);
    github = new FakeGitHub();
    gitCli = new GitCli(new ProcessCommandRunner());
    origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), REPO);
    clones = new ArrayList<>();
    checkResults = new ArrayDeque<>();
    agent =
        new ScriptedAgent((n, work) -> {
          write(work.resolve("out/holdout/noir/holdout/T.java"), GOOD_TEST);
          write(
              work.resolve("out/reference/src/main/java/com/example/api/Foo.java"),
              "package com.example.api;\npublic interface Foo {}\n");
        });
    SealCheckRunner checks =
        (inputs, out) ->
            checkResults.isEmpty()
                ? SealArtifactsFixture.good(out)
                : checkResults.pop().apply(inputs, out);
    var clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
    var config = NoirConfig.defaults();
    var git = new LocalGit();
    sealer =
        new ApprovalSealer(
            store,
            github,
            new RepoMirror(git, home.repos()),
            git,
            new SuiteSealer(store, new Examiner(agent, config), checks, config, clock),
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
    public boolean exists(Path repoDir, String sha, String path) {
      return gitCli.exists(repoDir, sha, path);
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
    assertThat(event("seal_rejected").data())
        .isEqualTo(Map.of("reason", reason.eventValue(), "problems", problems));
    assertThat(github.comments(issue)).as("the caller posts rejection comments").isEmpty();
    assertThat(eventTypes()).doesNotContain("sealed", "commitment_posted");
    for (var leftover :
        List.of("spec.json", "stubs", "base.bundle", "holdout", "reference", "commitment.json")) {
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
                "Add a bounded cache"));
    assertThat(event("sealed").data())
        .isEqualTo(
            Map.of(
                "issue", issue,
                "commitmentSha256", sha,
                "baseSha", originHead(),
                "bodySha256", bodySha));

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
  void rejectionCleansPartialFiles() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    }
    sealer.seal(item);
    assertRejected(
        new SealOutcome.Rejected(
            SealRejection.SUITE_TOO_WEAK,
            ApprovalComments.suiteTooWeak(3, List.of("1 hidden test passed against the stubs"))),
        SealRejection.SUITE_TOO_WEAK,
        ApprovalComments.suiteTooWeak(3, List.of("1 hidden test passed against the stubs")),
        1);
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
    assertThat(event("sealed").data()).containsEntry("baseSha", originHead());
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
                + " spec check --issue <repo>#<n>` shows the same list), then add `noir:ready`"
                + " again.");
    assertThat(ApprovalComments.stubExists(List.of("x/A.java")))
        .isEqualTo(
            "noir did not seal this issue: these API stub files already exist on the default"
                + " branch:\n\n- x/A.java\n\nStubs may only add new files. Revise the spec, then"
                + " add `noir:ready` again.");
    assertThat(ApprovalComments.suiteTooWeak(3, List.of("p", "q")))
        .isEqualTo(
            "noir could not produce a hidden test suite that passes its checks after 3"
                + " attempts:\n\n- p\n- q\n\nThis usually means a requirement is ambiguous or"
                + " untestable as written. Revise the spec, then add `noir:ready` again.");
    assertThat(ApprovalComments.sealed("c", "b", "s"))
        .isEqualTo("noir commitment `c` (commitmentVersion 2, base `b`, issue body sha256 `s`)");
  }
}
