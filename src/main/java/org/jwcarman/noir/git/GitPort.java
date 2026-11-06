package org.jwcarman.noir.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The git operations noir needs on a local clone of the target repository. */
public interface GitPort {
  void fetch(Path repoDir, String branch);

  /** The commit SHA a ref resolves to. */
  String revParse(Path repoDir, String ref);

  /** The {@code owner/name} of the {@code origin} remote. */
  String originRepo(Path repoDir);

  /** The branch a {@link #bundle} holds its commit on. */
  String SEAL_BRANCH = "noir/seal-base";

  /**
   * Writes a git bundle containing {@code sha} and its history to {@code out}, on branch {@link
   * #SEAL_BRANCH}.
   */
  void bundle(Path repoDir, String sha, Path out);

  /** Clones {@code repo} ({@code owner/name}) into {@code dir} using the {@code gh} credentials. */
  void cloneRepo(String repo, Path dir);

  /** Whether {@code path} exists in the tree of commit {@code sha}. */
  boolean exists(Path repoDir, String sha, String path);

  /** The content of {@code path} at {@code rev}, or empty when the path does not exist there. */
  Optional<String> show(Path repoDir, String rev, String path);

  /**
   * The paths of every file under {@code pathPrefix} (a directory, such as {@code src/main/java/})
   * in the tree of {@code rev}, sorted; empty when there are none.
   */
  List<String> listFiles(Path repoDir, String rev, String pathPrefix);

  /**
   * {@code git diff --no-index -U0} of two files under {@code dir} (paths relative to it), with
   * {@code a/} and {@code b/} prefixes; empty when they are equal.
   */
  String diffNoIndex(Path dir, String oldPath, String newPath);
}
