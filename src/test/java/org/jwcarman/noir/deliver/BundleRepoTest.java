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
    // positive control: the planted hook and fsmonitor really fire when git runs in the source
    git(fx.source, "status");
    git(fx.source, "checkout", "-q", "-b", "tmp-control");
    assertThat(hookMarker).exists();
    assertThat(fsmonitorMarker).exists();
    git(fx.source, "checkout", "-q", fx.branch);
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
  void truncatedPatchIsNeverReturned() {
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false, false))
            .whenArgvStartsWith(List.of("git", "-C"), new CommandResult(0, "", "", false, false));
    try (var repo = BundleRepo.open(fake, fakeBundle(), tmp)) {
      fake.whenArgvStartsWith(
          List.of("git", "-C", repo.dir().toString(), "log"),
          new CommandResult(0, "+partial", "", false, true));
      assertThatThrownBy(() -> repo.patchSince("base", "br"))
          .isInstanceOf(PatchTooLargeException.class);
      var log = fake.commands().get(fake.commands().size() - 1);
      assertThat(log.argv()).contains("log");
      assertThat(log.outputLimit()).isEqualTo(BundleRepo.MAX_PATCH_BYTES);
    }
  }

  @Test
  void everyGitInvocationUsesHardenedEnv() {
    var fake =
        new FakeCommandRunner()
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "abc\n", "", false, false));
    try (var repo = BundleRepo.open(fake, fakeBundle(), tmp)) {
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
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false, false))
            .whenArgvStartsWith(
                List.of("git", "-C"),
                new CommandResult(128, "", "fatal: bad bundle", false, false));
    // init has no -C so it succeeds; fetch fails
    assertThatThrownBy(() -> BundleRepo.open(fake, fakeBundle(), work))
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
            .whenArgvStartsWith(List.of("git"), new CommandResult(0, "", "", false, false));
    var repo = BundleRepo.open(fake, fakeBundle(), tmp.resolve("w2"));
    assertThat(repo.dir()).exists();
    repo.close();
    repo.close();
    assertThat(repo.dir()).doesNotExist();
  }

  private Path fakeBundle() {
    try {
      var p = tmp.resolve("fake-" + System.nanoTime() + ".bundle");
      Files.writeString(p, "# v2 git bundle\n\n");
      return p;
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private void assertRejected(Path bundle, String message) throws IOException {
    var work = Files.createDirectories(tmp.resolve("rw-" + System.nanoTime()));
    assertThatThrownBy(() -> BundleRepo.open(new ProcessCommandRunner(), bundle, work))
        .isInstanceOf(GitException.class)
        .hasMessageContaining(message);
    try (var s = Files.list(work)) {
      assertThat(s).isEmpty();
    }
  }

  @Test
  void rejectsSymlinkToHostileRepoWithoutRunningItsHooks() throws IOException {
    var fx = BundleFixture.create(tmp.resolve("fx"), "noir/1-x");
    var marker = tmp.resolve("marker").toAbsolutePath();
    var hook = fx.source.resolve(".git/hooks/post-checkout");
    Files.createDirectories(hook.getParent());
    Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\n");
    hook.toFile().setExecutable(true);
    var link = tmp.resolve("evil.bundle");
    Files.createSymbolicLink(link, fx.source);
    assertRejected(link, "Not a regular bundle file");
    assertThat(marker).doesNotExist();
  }

  @Test
  void rejectsSymlinkToRegularBundleFile() throws IOException {
    var fx = BundleFixture.create(tmp.resolve("fx"), "noir/1-x").bundled();
    var link = tmp.resolve("link.bundle");
    Files.createSymbolicLink(link, fx.bundle);
    assertRejected(link, "Not a regular bundle file");
  }

  @Test
  void rejectsDirectory() throws IOException {
    assertRejected(Files.createDirectories(tmp.resolve("dir.bundle")), "Not a regular bundle file");
  }

  @Test
  void rejectsWrongHeader() throws IOException {
    var f = tmp.resolve("bad.bundle");
    Files.writeString(f, "not a bundle\n");
    assertRejected(f, "Not a git bundle");
  }

  @Test
  void patchIncludesBinaryContentAndMergeResolutions() throws IOException {
    var fx = BundleFixture.create(tmp.resolve("fx"), "noir/1-x");
    var src = fx.source;
    Files.write(src.resolve("secret.bin"), "\0AKIAIOSFODNN7EXAMPLE\0".getBytes());
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "binary");
    git(src, "checkout", "-q", "-b", "side", fx.baseSha);
    Files.writeString(src.resolve("Added.txt"), "side\n");
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "side");
    git(src, "checkout", "-q", fx.branch);
    try {
      git(src, "merge", "-q", "--no-ff", "side", "-m", "merge");
    } catch (IllegalStateException expected) {
      // conflict on Added.txt: resolve with an injected string
    }
    Files.writeString(src.resolve("Added.txt"), "ghp_MERGEINTRODUCEDTOKEN\n");
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "merge");
    git(src, "bundle", "create", "-q", fx.bundle.toString(), "refs/heads/" + fx.branch);
    try (var repo = BundleRepo.open(new ProcessCommandRunner(), fx.bundle, tmp)) {
      var patch = repo.patchSince(fx.baseSha, fx.branch);
      assertThat(patch).contains("AKIAIOSFODNN7EXAMPLE").contains("ghp_MERGEINTRODUCEDTOKEN");
    }
  }
}
