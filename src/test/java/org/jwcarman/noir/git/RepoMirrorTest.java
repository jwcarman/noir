package org.jwcarman.noir.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoMirrorTest {
  @TempDir Path tmp;

  static class RecordingGit implements GitPort {
    final List<String> calls = new ArrayList<>();

    @Override
    public void fetch(Path repoDir, String branch) {
      calls.add("fetch " + repoDir + " " + branch);
    }

    @Override
    public String revParse(Path repoDir, String ref) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String originRepo(Path repoDir) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void bundle(Path repoDir, String sha, Path out) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void cloneRepo(String repo, Path dir) {
      assertThat(dir.getParent()).isDirectory();
      calls.add("clone " + repo + " " + dir);
      try {
        Files.createDirectories(dir.resolve(".git"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public boolean exists(Path repoDir, String sha, String path) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void clonesOnceThenFetches() {
    var git = new RecordingGit();
    var root = tmp.resolve("repos");
    var mirror = new RepoMirror(git, root);
    var dir = root.resolve("jwcarman/demo");

    assertThat(mirror.sync("jwcarman/demo", "main")).isEqualTo(dir);
    assertThat(mirror.sync("jwcarman/demo", "trunk")).isEqualTo(dir);

    assertThat(git.calls)
        .containsExactly(
            "clone jwcarman/demo " + dir, "fetch " + dir + " main", "fetch " + dir + " trunk");
  }

  @Test
  void fetchFailurePropagates() {
    var git =
        new RecordingGit() {
          @Override
          public void fetch(Path repoDir, String branch) {
            throw new GitException("fetch failed");
          }
        };
    var mirror = new RepoMirror(git, tmp.resolve("repos"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mirror.sync("a/b", "main"))
        .isInstanceOf(GitException.class);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"../x", "a/..", "./b", "a/.", "/abs/path", "a/b/c", "a", "a/", "/a", "a\\b/c", ""})
  void rejectsRepositoriesThatAreNotOwnerSlashName(String repo) {
    var git = new RecordingGit();
    var mirror = new RepoMirror(git, tmp.resolve("repos"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mirror.sync(repo, "main"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid repository: " + repo);
    assertThat(git.calls).isEmpty();
  }

  @Test
  void rejectsAnAbsolutePath() {
    var git = new RecordingGit();
    var mirror = new RepoMirror(git, tmp.resolve("repos"));
    var abs = tmp.resolve("elsewhere/repo").toAbsolutePath().toString();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mirror.sync(abs, "main"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid repository: " + abs);
    assertThat(git.calls).isEmpty();
  }

  @Test
  void acceptsOwnerSlashNameWithDotsDashesAndUnderscores() {
    var git = new RecordingGit();
    var root = tmp.resolve("repos");
    var mirror = new RepoMirror(git, root);
    assertThat(mirror.sync("jwcarman/noir-test", "main"))
        .isEqualTo(root.resolve("jwcarman/noir-test"));
    assertThat(mirror.sync("a_b.c/x.y-z_1", "main")).isEqualTo(root.resolve("a_b.c/x.y-z_1"));
  }
}
