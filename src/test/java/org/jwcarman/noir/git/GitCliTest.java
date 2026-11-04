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
}
