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
  void tipIsTheBranchHeadInTheBundle() throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("origin"), "o/r");
    var src = GitFixtures.cloneOf(origin, tmp.resolve("src"));
    git(src, "checkout", "-q", "-b", "noir/1-x");
    Files.writeString(src.resolve("A.txt"), "a\n");
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "add a");
    var head = git(src, "rev-parse", "HEAD").trim();
    var bundle = tmp.resolve("out.bundle");
    git(src, "bundle", "create", "-q", bundle.toString(), "refs/heads/noir/1-x");

    var tip =
        new GitBundles(new ProcessCommandRunner(), tmp.resolve("scratch")).tip(bundle, "noir/1-x");

    assertThat(tip).isEqualTo(head).hasSize(40);
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

  @Test
  void fileChangesReportsEachStatusAndCounts() throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("origin"), "o/r");
    var src = GitFixtures.cloneOf(origin, tmp.resolve("src"));
    Files.writeString(src.resolve("mod.txt"), "a\nb\nc\n");
    Files.writeString(src.resolve("del.txt"), "x\ny\n");
    Files.writeString(src.resolve("old.txt"), "keep1\nkeep2\nkeep3\nkeep4\n");
    git(src, "add", ".");
    git(src, "commit", "-q", "-m", "setup");
    var base = git(src, "rev-parse", "HEAD").trim();
    git(src, "checkout", "-q", "-b", "noir/1-x");
    Files.writeString(src.resolve("mod.txt"), "a\nB\nc\nd\n");
    Files.delete(src.resolve("del.txt"));
    git(src, "mv", "old.txt", "new.txt");
    Files.writeString(src.resolve("add.txt"), "1\n2\n3\n");
    Files.write(src.resolve("bin.dat"), new byte[] {0, 1, 2, 0, 3});
    git(src, "add", "-A");
    git(src, "commit", "-q", "-m", "change");
    var bundle = tmp.resolve("out.bundle");
    git(src, "bundle", "create", "-q", bundle.toString(), "refs/heads/noir/1-x");

    var changes =
        new GitBundles(new ProcessCommandRunner(), tmp.resolve("scratch"))
            .fileChanges(bundle, base, "noir/1-x");

    assertThat(changes)
        .containsExactly(
            new FileChange(FileChange.Status.ADDED, "add.txt", null, 3, 0),
            new FileChange(FileChange.Status.ADDED, "bin.dat", null, 0, 0),
            new FileChange(FileChange.Status.DELETED, "del.txt", null, 0, 2),
            new FileChange(FileChange.Status.MODIFIED, "mod.txt", null, 2, 1),
            new FileChange(FileChange.Status.RENAMED, "new.txt", "old.txt", 0, 0));
    try (var left = Files.list(tmp.resolve("scratch"))) {
      assertThat(left).isEmpty();
    }
  }

  @Test
  void fileChangesHandlesSpacesAndUnicodeInPaths() throws Exception {
    var origin = GitFixtures.originWithCommit(tmp.resolve("origin"), "o/r");
    var src = GitFixtures.cloneOf(origin, tmp.resolve("src"));
    var base = git(src, "rev-parse", "HEAD").trim();
    git(src, "checkout", "-q", "-b", "noir/1-x");
    Files.createDirectories(src.resolve("dir with space"));
    Files.writeString(src.resolve("dir with space/caf\u00e9 \u65e5\u672c.txt"), "hi\n");
    git(src, "add", "-A");
    git(src, "commit", "-q", "-m", "unicode");
    var bundle = tmp.resolve("out.bundle");
    git(src, "bundle", "create", "-q", bundle.toString(), "refs/heads/noir/1-x");

    var changes =
        new GitBundles(new ProcessCommandRunner(), tmp.resolve("scratch"))
            .fileChanges(bundle, base, "noir/1-x");

    assertThat(changes)
        .containsExactly(
            new FileChange(
                FileChange.Status.ADDED, "dir with space/caf\u00e9 \u65e5\u672c.txt", null, 1, 0));
  }
}
