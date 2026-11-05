package org.jwcarman.noir.git;

import java.nio.file.Path;

/** The git operations noir needs on a local clone of the target repository. */
public interface GitPort {
  void fetch(Path repoDir, String branch);

  /** The commit SHA a ref resolves to. */
  String revParse(Path repoDir, String ref);

  /** The {@code owner/name} of the {@code origin} remote. */
  String originRepo(Path repoDir);

  /** Writes a git bundle containing {@code sha} and its history to {@code out}. */
  void bundle(Path repoDir, String sha, Path out);

  /** Clones {@code repo} ({@code owner/name}) into {@code dir} using the {@code gh} credentials. */
  void cloneRepo(String repo, Path dir);

  /** Whether {@code path} exists in the tree of commit {@code sha}. */
  boolean exists(Path repoDir, String sha, String path);
}
