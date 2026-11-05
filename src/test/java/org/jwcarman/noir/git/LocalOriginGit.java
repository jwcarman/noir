package org.jwcarman.noir.git;

import java.nio.file.Path;

/** GitCli, except clones come from a local origin instead of {@code gh}. */
public record LocalOriginGit(GitCli cli, Path origin) implements GitPort {
  @Override
  public void fetch(Path repoDir, String branch) {
    cli.fetch(repoDir, branch);
  }

  @Override
  public String revParse(Path repoDir, String ref) {
    return cli.revParse(repoDir, ref);
  }

  @Override
  public String originRepo(Path repoDir) {
    return cli.originRepo(repoDir);
  }

  @Override
  public void bundle(Path repoDir, String sha, Path out) {
    cli.bundle(repoDir, sha, out);
  }

  @Override
  public boolean exists(Path repoDir, String sha, String path) {
    return cli.exists(repoDir, sha, path);
  }

  @Override
  public void cloneRepo(String repo, Path dir) {
    GitFixtures.cloneOf(origin, dir);
  }
}
