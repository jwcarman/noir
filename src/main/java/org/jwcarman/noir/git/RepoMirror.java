package org.jwcarman.noir.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Server-side clones of target repositories, one per {@code owner/name} under a root. */
public final class RepoMirror {
  private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.-]+");

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
    var dir = cloneDir(repo);
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

  /** {@code reposRoot/<owner>/<name>}; rejects anything that is not a plain {@code owner/name}. */
  private Path cloneDir(String repo) {
    var parts = repo.split("/", -1);
    var valid =
        parts.length == 2
            && Arrays.stream(parts)
                .allMatch(p -> PART.matcher(p).matches() && !p.equals(".") && !p.equals(".."));
    var root = reposRoot.normalize();
    var dir = valid ? reposRoot.resolve(parts[0]).resolve(parts[1]) : null;
    if (dir == null || !dir.normalize().startsWith(root) || dir.normalize().equals(root)) {
      throw new IllegalArgumentException("invalid repository: " + repo);
    }
    return dir;
  }
}
