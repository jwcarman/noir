package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.noir.git.GitFixtures.git;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitFixtures;

class GitBundlesTest {
  @TempDir Path tmp;

  @Test
  void readsCommitsAndPatchFromABundle() throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("origin"), "o/r");
    var src = GitFixtures.cloneOf(origin, tmp.resolve("src"));
    var base = git(src, "rev-parse", "HEAD").trim();
    git(src, "checkout", "-q", "-b", "noir/1-x");
    Files.writeString(src.resolve("Key.txt"), "token=abc123\n");
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "add key");
    var bundle = tmp.resolve("out.bundle");
    git(src, "bundle", "create", "-q", bundle.toString(), "refs/heads/noir/1-x");

    var bundles = new GitBundles(new ProcessCommandRunner(), tmp.resolve("scratch"));

    assertThat(bundles.commitsSince(bundle, base, "noir/1-x")).hasSize(1);
    assertThat(bundles.patchSince(bundle, base, "noir/1-x")).contains("+token=abc123");
    try (var left = Files.list(tmp.resolve("scratch"))) {
      assertThat(left).isEmpty();
    }
  }
}
