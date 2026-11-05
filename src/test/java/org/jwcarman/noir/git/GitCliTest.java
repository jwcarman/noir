package org.jwcarman.noir.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.noir.git.GitFixtures.git;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.exec.ProcessCommandRunner;

class GitCliTest {
  final GitPort gitPort = new GitCli(new ProcessCommandRunner());

  @Test
  void pinsOriginDefaultBranchNotLocalHead(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    var originSha = gitPort.revParse(clone, "origin/main");
    Files.writeString(clone.resolve("local.txt"), "x");
    git(clone, "add", ".");
    git(clone, "commit", "-qm", "local only");
    gitPort.fetch(clone, "main");
    assertThat(gitPort.revParse(clone, "origin/main")).isEqualTo(originSha).hasSize(40);
    assertThat(gitPort.revParse(clone, "HEAD")).isNotEqualTo(originSha);
  }

  @Test
  void fetchSeesNewRemoteCommits(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    var other = GitFixtures.cloneOf(origin, tmp.resolve("other"));
    Files.writeString(other.resolve("new.txt"), "y");
    git(other, "add", ".");
    git(other, "commit", "-qm", "remote change");
    git(other, "push", "-q", "origin", "main");
    gitPort.fetch(clone, "main");
    assertThat(gitPort.revParse(clone, "origin/main")).isEqualTo(gitPort.revParse(other, "HEAD"));
  }

  @Test
  void fetchUpdatesDefaultBranchEvenWithNarrowRefspec(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    git(clone, "remote", "set-branches", "origin", "feat");
    var other = GitFixtures.cloneOf(origin, tmp.resolve("other"));
    Files.writeString(other.resolve("new.txt"), "y");
    git(other, "add", ".");
    git(other, "commit", "-qm", "remote change");
    git(other, "push", "-q", "origin", "main");
    gitPort.fetch(clone, "main");
    assertThat(gitPort.revParse(clone, "origin/main")).isEqualTo(gitPort.revParse(other, "HEAD"));
  }

  @Test
  void neverPromptsForCredentials() {
    var runner =
        new org.jwcarman.noir.exec.FakeCommandRunner()
            .whenArgvStartsWith(
                java.util.List.of("git"),
                new org.jwcarman.noir.exec.CommandResult(0, "", "", false, false));
    new GitCli(runner).fetch(Path.of("/repo"), "main");
    var c = runner.commands().getFirst();
    assertThat(c.env()).containsEntry("GIT_TERMINAL_PROMPT", "0");
    assertThat(c.argv())
        .containsExactly(
            "git",
            "-C",
            "/repo",
            "fetch",
            "--quiet",
            "origin",
            "+refs/heads/main:refs/remotes/origin/main");
  }

  @Test
  void parsesOriginRepo(@TempDir Path tmp) {
    var clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("r"), "jwcarman/demo"), tmp.resolve("c"));
    assertThat(gitPort.originRepo(clone)).isEqualTo("jwcarman/demo");
  }

  @Test
  void parsesUrlForms() {
    assertThat(GitCli.ownerRepo("https://github.com/jwcarman/demo.git")).isEqualTo("jwcarman/demo");
    assertThat(GitCli.ownerRepo("https://github.com/jwcarman/demo")).isEqualTo("jwcarman/demo");
    assertThat(GitCli.ownerRepo("git@github.com:jwcarman/demo.git")).isEqualTo("jwcarman/demo");
    assertThat(GitCli.ownerRepo("https://github.com/jwcarman/demo/")).isEqualTo("jwcarman/demo");
  }

  @Test
  void bundleClonesToPinnedShaAndLeavesNoRef(@TempDir Path tmp) {
    var clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("r"), "jwcarman/demo"), tmp.resolve("c"));
    var sha = gitPort.revParse(clone, "origin/main");
    gitPort.bundle(clone, sha, tmp.resolve("base.bundle"));
    git(tmp, "clone", "-q", tmp.resolve("base.bundle").toString(), "restored");
    git(tmp.resolve("restored"), "checkout", "-q", sha);
    assertThat(gitPort.revParse(tmp.resolve("restored"), "HEAD")).isEqualTo(sha);
    assertThat(git(clone, "for-each-ref", "refs/heads/noir")).isEmpty();
  }

  @Test
  void badRefFails(@TempDir Path tmp) {
    var clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("r"), "jwcarman/demo"), tmp.resolve("c"));
    assertThatThrownBy(() -> gitPort.revParse(clone, "nope"))
        .isInstanceOf(GitException.class)
        .hasMessageStartingWith("git rev-parse failed");
  }

  @Test
  void cloneRepoUsesGh() {
    var runner =
        new org.jwcarman.noir.exec.FakeCommandRunner()
            .whenArgvStartsWith(
                java.util.List.of("gh"),
                new org.jwcarman.noir.exec.CommandResult(0, "", "", false, false));
    new GitCli(runner).cloneRepo("o/r", Path.of("/work/r"));
    assertThat(runner.commands().getFirst().argv())
        .containsExactly("gh", "repo", "clone", "o/r", "/work/r", "--", "--quiet");
  }

  @Test
  void existsTrueFalseAndError() {
    var runner = new org.jwcarman.noir.exec.FakeCommandRunner();
    var p = Path.of("/repo");
    runner.whenArgvStartsWith(
        java.util.List.of("git", "-C", "/repo", "cat-file", "-e", "abc:yes"),
        new org.jwcarman.noir.exec.CommandResult(0, "", "", false, false));
    runner.whenArgvStartsWith(
        java.util.List.of("git", "-C", "/repo", "cat-file", "-e", "abc:no"),
        new org.jwcarman.noir.exec.CommandResult(128, "", "fatal: path", false, false));
    runner.whenArgvStartsWith(
        java.util.List.of("git", "-C", "/repo", "cat-file", "-e", "abc:err"),
        new org.jwcarman.noir.exec.CommandResult(1, "", "boom", false, false));
    var g = new GitCli(runner);
    assertThat(g.exists(p, "abc", "yes")).isTrue();
    assertThat(g.exists(p, "abc", "no")).isFalse();
    assertThatThrownBy(() -> g.exists(p, "abc", "err")).isInstanceOf(GitException.class);
  }
}
