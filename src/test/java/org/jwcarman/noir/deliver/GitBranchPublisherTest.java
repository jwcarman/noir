package org.jwcarman.noir.deliver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.noir.git.GitFixtures.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.FakeCommandRunner;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitException;

class GitBranchPublisherTest {
  @TempDir Path tmp;

  @Test
  void pushesBundleBranchToRemote() throws IOException {
    var fx = BundleFixture.create(tmp.resolve("fx"), "noir/1-x").bundled();
    var remote = Files.createDirectories(tmp.resolve("remote.git"));
    git(remote, "init", "--bare", "-q");
    var work = Files.createDirectories(tmp.resolve("work"));

    var publisher =
        new GitBranchPublisher(new ProcessCommandRunner(), work, repo -> remote.toString());
    publisher.push("o/r", fx.bundle, fx.branch);

    assertThat(git(remote, "rev-parse", "noir/1-x").trim()).isEqualTo(fx.headSha);
    try (var s = Files.list(work)) {
      assertThat(s).isEmpty();
    }
  }

  @Test
  void productionUrlAndHardenedEnv() {
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false));
    new GitBranchPublisher(fake, tmp).push("o/r", stubBundle(), "noir/1-x");
    var push = fake.commands().get(fake.commands().size() - 1);
    assertThat(push.argv())
        .contains(
            "push",
            "--force",
            "https://github.com/o/r.git",
            "refs/heads/noir/1-x:refs/heads/noir/1-x",
            "credential.helper=!gh auth git-credential");
    for (var c : fake.commands()) {
      assertThat(c.env())
          .containsEntry("GIT_TERMINAL_PROMPT", "0")
          .containsEntry("GIT_CONFIG_NOSYSTEM", "1")
          .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null");
    }
  }

  @Test
  void pushFailureThrowsGitException() {
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false))
            .whenArgvStartsWith(List.of("git", "-C"), new CommandResult(0, "", "", false));
    var failing =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(1, "", "denied", false));
    assertThatThrownBy(() -> new GitBranchPublisher(failing, tmp).push("o/r", stubBundle(), "br"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("denied");
  }

  private Path stubBundle() {
    try {
      var p = tmp.resolve("stub.bundle");
      Files.writeString(p, "# v2 git bundle\n\n");
      return p;
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
