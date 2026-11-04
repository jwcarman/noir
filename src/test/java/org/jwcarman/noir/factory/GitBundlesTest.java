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

  @Test
  void secretInsideABinaryFileMarkedBinaryByTheBuilderIsInThePatch() throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("origin"), "o/r");
    var src = GitFixtures.cloneOf(origin, tmp.resolve("src"));
    var base = git(src, "rev-parse", "HEAD").trim();
    git(src, "checkout", "-q", "-b", "noir/1-x");
    Files.writeString(src.resolve(".gitattributes"), "* -diff -text binary\n");
    var secret = "sk-ant-api03-binarysecret";
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(new byte[] {0, 1, 2, 0, (byte) 0xff});
    bytes.write(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.write(new byte[] {0, 0, 7});
    Files.write(src.resolve("blob.bin"), bytes.toByteArray());
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "add blob");
    var bundle = tmp.resolve("out.bundle");
    git(src, "bundle", "create", "-q", bundle.toString(), "refs/heads/noir/1-x");

    var patch =
        new GitBundles(new ProcessCommandRunner(), tmp.resolve("scratch"))
            .patchSince(bundle, base, "noir/1-x");

    assertThat(patch).contains("blob.bin").contains(secret);
  }
}
