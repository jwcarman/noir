package org.jwcarman.noir.deliver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class BundleRepoTest {
  @TempDir Path tmp;

  @Test
  void inspectsBundleAndNeverRunsHooksOrConfigFromTheSourceRepo() throws IOException {
    var fx = BundleFixture.create(tmp.resolve("fx"), "noir/1-x");
    var markers = Files.createDirectories(tmp.resolve("markers"));
    var hookMarker = markers.resolve("hook").toAbsolutePath();
    var fsmonitorMarker = markers.resolve("fsmonitor").toAbsolutePath();

    // plant hostile hooks/config only after the commit
    var hook = fx.source.resolve(".git/hooks/post-checkout");
    Files.createDirectories(hook.getParent());
    Files.writeString(hook, "#!/bin/sh\ntouch '" + hookMarker + "'\n");
    assertThat(hook.toFile().setExecutable(true)).isTrue();
    var fsmonitor = fx.source.resolve(".git/fsmonitor.sh");
    Files.writeString(fsmonitor, "#!/bin/sh\ntouch '" + fsmonitorMarker + "'\n");
    assertThat(fsmonitor.toFile().setExecutable(true)).isTrue();
    Files.writeString(
        fx.source.resolve(".git/config"),
        Files.readString(fx.source.resolve(".git/config"))
            + "[core]\n\tfsmonitor = "
            + fsmonitor.toAbsolutePath()
            + "\n");
    fx.bundled();
    Files.deleteIfExists(hookMarker);
    Files.deleteIfExists(fsmonitorMarker);

    var tempRoot = Files.createDirectories(tmp.resolve("work"));
    Path dir;
    try (var repo = BundleRepo.open(new ProcessCommandRunner(), fx.bundle, tempRoot)) {
      dir = repo.dir();
      assertThat(repo.commitsSince(fx.baseSha, fx.branch)).containsExactly(fx.headSha);
      assertThat(repo.patchSince(fx.baseSha, fx.branch)).contains("Added.txt").contains("+hello");
      assertThat(dir).startsWith(tempRoot);
    }
    assertThat(dir).doesNotExist();
    assertThat(hookMarker).doesNotExist();
    assertThat(fsmonitorMarker).doesNotExist();
  }

  @Test
  void everyGitInvocationUsesHardenedEnv() {
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "abc\n", "", false));
    try (var repo = BundleRepo.open(fake, tmp.resolve("b.bundle"), tmp)) {
      repo.commitsSince("base", "br");
      repo.patchSince("base", "br");
    }
    assertThat(fake.commands()).hasSize(4);
    for (var c : fake.commands()) {
      assertThat(c.env())
          .containsEntry("GIT_TERMINAL_PROMPT", "0")
          .containsEntry("GIT_CONFIG_NOSYSTEM", "1")
          .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null");
    }
    assertThat(fake.commands().get(0).argv()).contains("init", "--bare");
    assertThat(fake.commands().get(1).argv()).contains("fetch", "+refs/heads/*:refs/heads/*");
    assertThat(fake.commands().get(2).argv()).contains("rev-list", "base..br");
    assertThat(fake.commands().get(3).argv()).contains("log", "-p", "--no-color", "base..br");
  }

  @Test
  void failedGitCommandThrowsAndTempDirIsStillDeleted() throws IOException {
    var work = Files.createDirectories(tmp.resolve("w"));
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false))
            .whenArgvStartsWith(
                List.of("git", "-C"), new CommandResult(128, "", "fatal: bad bundle", false));
    // init has no -C so it succeeds; fetch fails
    assertThatThrownBy(() -> BundleRepo.open(fake, tmp.resolve("b.bundle"), work))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("fatal: bad bundle");
    try (var s = Files.list(work)) {
      assertThat(s).isEmpty();
    }
  }

  @Test
  void closeIsIdempotent() {
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false));
    var repo = BundleRepo.open(fake, tmp.resolve("b.bundle"), tmp.resolve("w2"));
    assertThat(repo.dir()).exists();
    repo.close();
    repo.close();
    assertThat(repo.dir()).doesNotExist();
  }
}
