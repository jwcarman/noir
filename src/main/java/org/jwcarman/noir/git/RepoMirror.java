package org.jwcarman.noir.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-side clones of target repositories, one per {@code owner/name} under a root. */
public final class RepoMirror {
  private final GitPort git;
  private final Path reposRoot;

  public RepoMirror(GitPort git, Path reposRoot) {
    this.git = git;
    this.reposRoot = reposRoot;
  }

  /**
   * Clones {@code repo} if it has no clone yet, then fetches {@code branch}. A broken clone is left
   * in place and its {@link GitException} propagates.
   */
  public Path sync(String repo, String branch) {
    var dir = reposRoot.resolve(repo);
    if (!Files.isDirectory(dir.resolve(".git"))) {
      try {
        Files.createDirectories(dir.getParent());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      git.cloneRepo(repo, dir);
    }
    git.fetch(dir, branch);
    return dir;
  }
}
