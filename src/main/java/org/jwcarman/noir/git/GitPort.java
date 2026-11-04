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
}
