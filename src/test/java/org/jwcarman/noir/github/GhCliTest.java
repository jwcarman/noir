package org.jwcarman.noir.github;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.FakeCommandRunner;

class GhCliTest {
  final FakeCommandRunner runner = new FakeCommandRunner();
  final GitHubPort gh = new GhCli(runner);

  static CommandResult ok(String stdout) {
    return new CommandResult(0, stdout, "", false);
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
            4, "", "To get started with GitHub CLI, please run:  gh auth login\n", false));
    assertThatThrownBy(() -> gh.defaultBranch("jwcarman/demo"))
        .isInstanceOf(GitHubException.class)
        .hasMessage("GitHub CLI is not authenticated; run: gh auth login");
  }

  @Test
  void otherFailuresCarryTail() {
    runner.whenArgvStartsWith(
        List.of("gh", "api"), new CommandResult(1, "", "HTTP 404: Not Found\n", false));
    assertThatThrownBy(() -> gh.defaultBranch("jwcarman/nope"))
        .hasMessage("gh api failed: HTTP 404: Not Found");
  }
}
