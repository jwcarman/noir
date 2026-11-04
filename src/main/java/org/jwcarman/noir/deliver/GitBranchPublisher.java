package org.jwcarman.noir.deliver;

import java.nio.file.Path;
import java.util.function.Function;
import org.jwcarman.noir.exec.CommandRunner;

/** Pushes a bundle's branch to GitHub from a noir-owned bare repository. */
public final class GitBranchPublisher implements BranchPublisher {
  private final CommandRunner runner;
  private final Path tempRoot;
  private final Function<String, String> remoteUrl;

  public GitBranchPublisher(CommandRunner runner, Path tempRoot) {
    this(runner, tempRoot, repo -> "https://github.com/" + repo + ".git");
  }

  GitBranchPublisher(CommandRunner runner, Path tempRoot, Function<String, String> remoteUrl) {
    this.runner = runner;
    this.tempRoot = tempRoot;
    this.remoteUrl = remoteUrl;
  }

  @Override
  public void push(String repo, Path bundle, String branch) {
    try (var bare = BundleRepo.open(runner, bundle, tempRoot)) {
      bare.git(
          "-c",
          "credential.helper=",
          "-c",
          "credential.helper=!gh auth git-credential",
          "push",
          "--force",
          remoteUrl.apply(repo),
          "refs/heads/" + branch + ":refs/heads/" + branch);
    }
  }
}
