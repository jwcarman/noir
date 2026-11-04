package org.jwcarman.noir.github;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.FakeCommandRunner;

class GhCliTest {
  final FakeCommandRunner runner = new FakeCommandRunner();
  final GitHubPort gh = new GhCli(runner);

  static CommandResult ok(String stdout) {
    return new CommandResult(0, stdout, "", false, false);
  }

  @Test
  void defaultBranch() {
    runner.whenArgvStartsWith(List.of("gh", "api"), ok("main\n"));
    assertThat(gh.defaultBranch("jwcarman/demo")).isEqualTo("main");
    assertThat(runner.commands().getFirst().argv())
        .containsExactly("gh", "api", "repos/jwcarman/demo", "--jq", ".default_branch");
  }

  @Test
  void createIssueParsesNumberFromUrl() {
    runner.whenArgvStartsWith(
        List.of("gh", "issue", "create"), ok("https://github.com/jwcarman/demo/issues/42\n"));
    assertThat(gh.createIssue("jwcarman/demo", "T", "body", List.of("noir:ready"))).isEqualTo(42);
    var c = runner.commands().getFirst();
    assertThat(c.argv())
        .containsExactly(
            "gh",
            "issue",
            "create",
            "--repo",
            "jwcarman/demo",
            "--title",
            "T",
            "--body-file",
            "-",
            "--label",
            "noir:ready");
    assertThat(new String(c.stdin(), UTF_8)).isEqualTo("body");
  }

  @Test
  void createIssueWithUnparseableOutputFails() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "create"), ok("something odd\n"));
    assertThatThrownBy(() -> gh.createIssue("jwcarman/demo", "T", "b", List.of()))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("something odd");
  }

  @Test
  void comment() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "comment"), ok(""));
    gh.comment("jwcarman/demo", 42, "hello");
    var c = runner.commands().getFirst();
    assertThat(c.argv())
        .containsExactly(
            "gh", "issue", "comment", "42", "--repo", "jwcarman/demo", "--body-file", "-");
    assertThat(new String(c.stdin(), UTF_8)).isEqualTo("hello");
  }

  @Test
  void ensureLabelsCreatesAllFive() {
    runner.whenArgvStartsWith(List.of("gh", "label", "create"), ok(""));
    gh.ensureLabels("jwcarman/demo");
    assertThat(runner.commands())
        .extracting(c -> c.argv())
        .containsExactly(
            List.of(
                "gh",
                "label",
                "create",
                "noir:ready",
                "--repo",
                "jwcarman/demo",
                "--color",
                "0e8a16",
                "--description",
                "Sealed; waiting for the factory",
                "--force"),
            List.of(
                "gh",
                "label",
                "create",
                "noir:building",
                "--repo",
                "jwcarman/demo",
                "--color",
                "fbca04",
                "--description",
                "The factory is working on this",
                "--force"),
            List.of(
                "gh",
                "label",
                "create",
                "noir:pr-open",
                "--repo",
                "jwcarman/demo",
                "--color",
                "1d76db",
                "--description",
                "PR opened by the factory",
                "--force"),
            List.of(
                "gh",
                "label",
                "create",
                "noir:failed",
                "--repo",
                "jwcarman/demo",
                "--color",
                "d93f0b",
                "--description",
                "The factory could not satisfy this",
                "--force"),
            List.of(
                "gh",
                "label",
                "create",
                "noir:blocked",
                "--repo",
                "jwcarman/demo",
                "--color",
                "b60205",
                "--description",
                "Needs human attention",
                "--force"));
  }

  @Test
  void unauthenticatedIsExplained() {
    runner.whenArgvStartsWith(
        List.of("gh", "api"),
        new CommandResult(
            4, "", "To get started with GitHub CLI, please run:  gh auth login\n", false, false));
    assertThatThrownBy(() -> gh.defaultBranch("jwcarman/demo"))
        .isInstanceOf(GitHubException.class)
        .hasMessage("GitHub CLI is not authenticated; run: gh auth login");
  }

  @Test
  void otherFailuresCarryTail() {
    runner.whenArgvStartsWith(
        List.of("gh", "api"), new CommandResult(1, "", "HTTP 404: Not Found\n", false, false));
    assertThatThrownBy(() -> gh.defaultBranch("jwcarman/nope"))
        .hasMessage("gh api failed: HTTP 404: Not Found");
  }

  @Test
  void openIssuesWithLabel() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "list"), ok("3\n7\n"));
    assertThat(gh.openIssuesWithLabel("r/d", "noir:ready")).containsExactly(3, 7);
    assertThat(runner.commands().getFirst().argv())
        .containsExactly(
            "gh",
            "issue",
            "list",
            "--repo",
            "r/d",
            "--label",
            "noir:ready",
            "--state",
            "open",
            "--json",
            "number",
            "--jq",
            ".[].number",
            "--limit",
            "200");
  }

  @Test
  void openIssuesWithLabelEmpty() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "list"), ok("\n"));
    assertThat(gh.openIssuesWithLabel("r/d", "noir:ready")).isEmpty();
  }

  @Test
  void issueOpen() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "view"), ok("OPEN\n"));
    assertThat(gh.issueOpen("r/d", 5)).isTrue();
    assertThat(runner.commands().getFirst().argv())
        .containsExactly(
            "gh", "issue", "view", "5", "--repo", "r/d", "--json", "state", "--jq", ".state");
  }

  @Test
  void issueClosed() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "view"), ok("CLOSED\n"));
    assertThat(gh.issueOpen("r/d", 5)).isFalse();
  }

  @Test
  void setNoirLabelAddsOneAndRemovesOthers() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "edit"), ok(""));
    gh.setNoirLabel("r/d", 1, Optional.of("noir:building"));
    assertThat(runner.commands().getFirst().argv())
        .containsExactly(
            "gh",
            "issue",
            "edit",
            "1",
            "--repo",
            "r/d",
            "--add-label",
            "noir:building",
            "--remove-label",
            "noir:ready,noir:pr-open,noir:failed,noir:blocked");
  }

  @Test
  void setNoirLabelEmptyRemovesAll() {
    runner.whenArgvStartsWith(List.of("gh", "issue", "edit"), ok(""));
    gh.setNoirLabel("r/d", 1, Optional.empty());
    assertThat(runner.commands().getFirst().argv())
        .containsExactly(
            "gh",
            "issue",
            "edit",
            "1",
            "--repo",
            "r/d",
            "--remove-label",
            "noir:ready,noir:building,noir:pr-open,noir:failed,noir:blocked");
  }

  @Test
  void findPullRequest() {
    runner.whenArgvStartsWith(List.of("gh", "pr", "list"), ok("12\n"));
    assertThat(gh.findPullRequest("r/d", "noir/1-x")).contains(12);
    assertThat(runner.commands().getFirst().argv())
        .containsExactly(
            "gh",
            "pr",
            "list",
            "--repo",
            "r/d",
            "--head",
            "noir/1-x",
            "--state",
            "all",
            "--json",
            "number",
            "--jq",
            ".[0].number");
  }

  @Test
  void findPullRequestNoneForEmptyOrNull() {
    runner.whenArgvStartsWith(List.of("gh", "pr", "list"), ok("\n"));
    assertThat(gh.findPullRequest("r/d", "b")).isEmpty();
    var runner2 = new FakeCommandRunner();
    runner2.whenArgvStartsWith(List.of("gh", "pr", "list"), ok("null\n"));
    assertThat(new GhCli(runner2).findPullRequest("r/d", "b")).isEmpty();
  }

  @Test
  void createPullRequestParsesNumber() {
    runner.whenArgvStartsWith(List.of("gh", "pr", "create"), ok("https://github.com/r/d/pull/9\n"));
    assertThat(gh.createPullRequest("r/d", "main", "noir/1-x", "T", "body", true)).isEqualTo(9);
    var c = runner.commands().getFirst();
    assertThat(c.argv())
        .containsExactly(
            "gh",
            "pr",
            "create",
            "--repo",
            "r/d",
            "--base",
            "main",
            "--head",
            "noir/1-x",
            "--title",
            "T",
            "--body-file",
            "-",
            "--draft");
    assertThat(new String(c.stdin(), UTF_8)).isEqualTo("body");
  }

  @Test
  void createPullRequestNotDraftAndBadOutput() {
    runner.whenArgvStartsWith(List.of("gh", "pr", "create"), ok("https://github.com/r/d/pull/9"));
    gh.createPullRequest("r/d", "main", "h", "T", "b", false);
    assertThat(runner.commands().getFirst().argv()).doesNotContain("--draft");
    var runner2 = new FakeCommandRunner();
    runner2.whenArgvStartsWith(List.of("gh", "pr", "create"), ok("weird"));
    assertThatThrownBy(() -> new GhCli(runner2).createPullRequest("r/d", "m", "h", "T", "b", false))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("weird");
  }

  private static final List<String> PR_VIEW =
      List.of(
          "gh", "pr", "view", "7", "--repo", "r/d", "--json", "number,state,headRefOid,mergedAt");

  @Test
  void pullRequestParsesMergedState() {
    runner.whenArgvStartsWith(
        List.of("gh", "pr", "view"),
        ok(
            "{\"number\":7,\"state\":\"MERGED\",\"headRefOid\":\"abc123\","
                + "\"mergedAt\":\"2026-11-04T10:15:30Z\"}"));
    var pr = gh.pullRequest("r/d", 7);
    assertThat(pr.number()).isEqualTo(7);
    assertThat(pr.status()).isEqualTo(PullRequestState.Status.MERGED);
    assertThat(pr.headSha()).isEqualTo("abc123");
    assertThat(pr.mergedAt()).isEqualTo(java.time.Instant.parse("2026-11-04T10:15:30Z"));
    assertThat(runner.commands().getFirst().argv()).containsExactlyElementsOf(PR_VIEW);
  }

  @Test
  void pullRequestParsesOpenStateWithNullMergedAt() {
    runner.whenArgvStartsWith(
        List.of("gh", "pr", "view"),
        ok("{\"number\":7,\"state\":\"OPEN\",\"headRefOid\":\"abc123\",\"mergedAt\":null}"));
    var pr = gh.pullRequest("r/d", 7);
    assertThat(pr.status()).isEqualTo(PullRequestState.Status.OPEN);
    assertThat(pr.mergedAt()).isNull();
    assertThat(runner.commands().getFirst().argv()).containsExactlyElementsOf(PR_VIEW);
  }

  @Test
  void pullRequestWithGarbageOutputIsAGitHubException() {
    runner.whenArgvStartsWith(List.of("gh", "pr", "view"), ok("not json at all"));
    assertThatThrownBy(() -> gh.pullRequest("r/d", 7)).isInstanceOf(GitHubException.class);
  }

  @Test
  void pullRequestWithNullHeadIsAGitHubException() {
    runner.whenArgvStartsWith(
        List.of("gh", "pr", "view"),
        ok("{\"number\":7,\"state\":\"MERGED\",\"headRefOid\":null,\"mergedAt\":null}"));
    assertThatThrownBy(() -> gh.pullRequest("r/d", 7)).isInstanceOf(GitHubException.class);
    var blankRunner = new FakeCommandRunner();
    var blank = new GhCli(blankRunner);
    blankRunner.whenArgvStartsWith(
        List.of("gh", "pr", "view"),
        ok("{\"number\":7,\"state\":\"MERGED\",\"headRefOid\":\" \",\"mergedAt\":null}"));
    assertThatThrownBy(() -> blank.pullRequest("r/d", 7)).isInstanceOf(GitHubException.class);
  }
}
