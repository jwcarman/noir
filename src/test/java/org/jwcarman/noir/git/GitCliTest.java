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
  void showReadsFileAtRevision(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    Files.writeString(clone.resolve("A.txt"), "one\ntwo\n");
    git(clone, "add", ".");
    git(clone, "commit", "-qm", "add a");
    var sha = gitPort.revParse(clone, "HEAD");
    Files.writeString(clone.resolve("A.txt"), "changed\n");
    assertThat(gitPort.show(clone, sha, "A.txt")).contains("one\ntwo\n");
  }

  @Test
  void showMissingPathIsEmpty(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    assertThat(gitPort.show(clone, "HEAD", "nope.txt")).isEmpty();
  }

  @Test
  void showBogusRevisionThrows(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    assertThatThrownBy(() -> gitPort.show(clone, "no-such-rev", "README.md"))
        .isInstanceOf(GitException.class);
  }

  @Test
  void showNotARepoThrows(@TempDir Path tmp) {
    assertThatThrownBy(() -> gitPort.show(tmp, "HEAD", "a.txt")).isInstanceOf(GitException.class);
  }

  @Test
  void showTruncatedOutputThrows() {
    var runner =
        new org.jwcarman.noir.exec.FakeCommandRunner()
            .whenArgvStartsWith(
                java.util.List.of("git", "-C", "/r", "cat-file"),
                new org.jwcarman.noir.exec.CommandResult(0, "", "", false, false))
            .whenArgvStartsWith(
                java.util.List.of("git", "-C", "/r", "show"),
                new org.jwcarman.noir.exec.CommandResult(0, "tail", "", false, true));
    assertThatThrownBy(() -> new GitCli(runner).show(Path.of("/r"), "HEAD", "a"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("truncated");
  }

  @Test
  void listFilesListsEveryFileUnderThePrefixAtRevision(@TempDir Path tmp) throws Exception {
    var origin =
        GitFixtures.originWithFiles(
            tmp.resolve("remotes"),
            "jwcarman/demo",
            java.util.Map.of(
                "src/main/java/com/example/A.java", "a",
                "src/main/java/com/example/deep/B.java", "b",
                "src/main/java/odd name.java", "c",
                "src/test/java/com/example/ATest.java", "t",
                "README.md", "r"));
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    var sha = gitPort.revParse(clone, "origin/main");
    Files.writeString(clone.resolve("src/main/java/Later.java"), "later");
    git(clone, "add", ".");
    git(clone, "commit", "-qm", "later");

    assertThat(gitPort.listFiles(clone, sha, "src/main/java/"))
        .containsExactly(
            "src/main/java/com/example/A.java",
            "src/main/java/com/example/deep/B.java",
            "src/main/java/odd name.java");
    assertThat(gitPort.listFiles(clone, sha, "src/nope/")).isEmpty();
  }

  @Test
  void listFilesOfABogusRevisionThrows(@TempDir Path tmp) throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("clone"));
    assertThatThrownBy(() -> gitPort.listFiles(clone, "no-such-rev", "src/"))
        .isInstanceOf(GitException.class);
  }

  @Test
  void listFilesTruncatedOutputThrows() {
    var runner =
        new org.jwcarman.noir.exec.FakeCommandRunner()
            .whenArgvStartsWith(
                java.util.List.of("git", "-C", "/r", "cat-file"),
                new org.jwcarman.noir.exec.CommandResult(0, "", "", false, false))
            .whenArgvStartsWith(
                java.util.List.of("git", "-C", "/r", "ls-tree"),
                new org.jwcarman.noir.exec.CommandResult(0, "a.java", "", false, true));
    assertThatThrownBy(() -> new GitCli(runner).listFiles(Path.of("/r"), "HEAD", "src/"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("truncated");
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

  @Test
  void diffNoIndexGivesTheChangedLines(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("base.java"), "a\nb\nc\n");
    Files.writeString(tmp.resolve("reference.java"), "a\nB\nc\nd\n");
    var diff = gitPort.diffNoIndex(tmp, "base.java", "reference.java");
    assertThat(diff).contains("+++ b/reference.java");
    assertThat(org.jwcarman.noir.measure.DiffLines.added(diff, java.util.Set.of("reference.java")))
        .containsEntry("reference.java", java.util.Set.of(2, 4));
  }

  /** A CRLF base and an LF reference differ only in line endings, which are not changes. */
  @Test
  void diffNoIndexIgnoresLineEndings(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("base.java"), "a\r\nb\r\nc\r\n");
    Files.writeString(tmp.resolve("reference.java"), "a\nB\nc\n");
    var diff = gitPort.diffNoIndex(tmp, "base.java", "reference.java");
    assertThat(org.jwcarman.noir.measure.DiffLines.added(diff, java.util.Set.of("reference.java")))
        .containsEntry("reference.java", java.util.Set.of(2));

    Files.writeString(tmp.resolve("reference.java"), "a\nb\nc\n");
    assertThat(gitPort.diffNoIndex(tmp, "base.java", "reference.java")).isEmpty();
  }

  @Test
  void diffNoIndexOfEqualFilesIsEmpty(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("base.java"), "a\n");
    Files.writeString(tmp.resolve("reference.java"), "a\n");
    assertThat(gitPort.diffNoIndex(tmp, "base.java", "reference.java")).isEmpty();
  }

  @Test
  void diffNoIndexOfAMissingFileThrows(@TempDir Path tmp) throws Exception {
    // git exits 1 for a file it cannot read, as it does for a difference, but prints no diff.
    Files.writeString(tmp.resolve("base.java"), "a\n");
    assertThatThrownBy(() -> gitPort.diffNoIndex(tmp, "base.java", "missing.java"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("Could not access");
  }

  @Test
  void diffNoIndexIgnoresUserPrefixAndExternalDiffConfig() {
    var runner =
        new org.jwcarman.noir.exec.FakeCommandRunner()
            .whenArgvStartsWith(
                java.util.List.of("git"),
                new org.jwcarman.noir.exec.CommandResult(1, "diff --git x", "", false, false));
    new GitCli(runner).diffNoIndex(Path.of("/d"), "o", "n");
    assertThat(runner.commands().getFirst().argv())
        .containsExactly(
            "git",
            "-C",
            "/d",
            "diff",
            "--no-index",
            "--no-color",
            "--no-ext-diff",
            "--no-textconv",
            "--ignore-cr-at-eol",
            "-U0",
            "--src-prefix=a/",
            "--dst-prefix=b/",
            "--",
            "o",
            "n");
  }
}
