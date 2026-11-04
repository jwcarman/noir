package org.jwcarman.noir.deliver;

import static org.jwcarman.noir.git.GitFixtures.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jwcarman.noir.git.GitFixtures;

/** Builds a bundle with one extra commit on a branch, from a source clone. */
final class BundleFixture {
  final Path source;
  final Path bundle;
  final String baseSha;
  final String headSha;
  final String branch;

  private BundleFixture(Path source, Path bundle, String baseSha, String headSha, String branch) {
    this.source = source;
    this.bundle = bundle;
    this.baseSha = baseSha;
    this.headSha = headSha;
    this.branch = branch;
  }

  static BundleFixture create(Path root, String branch) {
    try {
      var origin = GitFixtures.originWithCommit(root.resolve("origin"), "o/r");
      var src = GitFixtures.cloneOf(origin, root.resolve("src"));
      var base = git(src, "rev-parse", "HEAD").trim();
      git(src, "checkout", "-q", "-b", branch);
      Files.writeString(src.resolve("Added.txt"), "hello\n");
      git(src, "add", ".");
      git(src, "commit", "-q", "-m", "add file");
      var head = git(src, "rev-parse", "HEAD").trim();
      return new BundleFixture(src, root.resolve("out.bundle"), base, head, branch);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  BundleFixture bundled() {
    git(source, "bundle", "create", "-q", bundle.toString(), "refs/heads/" + branch);
    return this;
  }
}
