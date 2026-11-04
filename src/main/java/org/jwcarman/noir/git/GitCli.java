package org.jwcarman.noir.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.CommandRunner;

/** {@link GitPort} backed by the {@code git} CLI. */
public final class GitCli implements GitPort {
  private static final String SEAL_REF = "refs/heads/noir/seal-base";

  private final CommandRunner runner;

  public GitCli(CommandRunner runner) {
    this.runner = runner;
  }

  @Override
  public void fetch(Path repoDir, String branch) {
    git(
        repoDir,
        "fetch",
        "--quiet",
        "origin",
        "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
  }

  @Override
  public String revParse(Path repoDir, String ref) {
    return git(repoDir, "rev-parse", "--verify", "--quiet", ref + "^{commit}").stdout().trim();
  }

  @Override
  public String originRepo(Path repoDir) {
    return ownerRepo(git(repoDir, "remote", "get-url", "origin").stdout().trim());
  }

  @Override
  public void bundle(Path repoDir, String sha, Path out) {
    git(repoDir, "update-ref", SEAL_REF, sha);
    try {
      git(repoDir, "bundle", "create", "--quiet", out.toAbsolutePath().toString(), SEAL_REF);
    } finally {
      git(repoDir, "update-ref", "-d", SEAL_REF);
    }
  }

  /** Parses {@code owner/name} from an https, scp-style, or filesystem remote URL. */
  public static String ownerRepo(String url) {
    var s = url.trim();
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    if (s.endsWith(".git")) {
      s = s.substring(0, s.length() - 4);
    }
    s = s.replace(':', '/');
    var parts = s.split("/");
    if (parts.length < 2) {
      throw new GitException("Cannot determine owner/name from remote URL: " + url);
    }
    return parts[parts.length - 2] + "/" + parts[parts.length - 1];
  }

  private CommandResult git(Path repoDir, String... args) {
    var argv = new ArrayList<String>(List.of("git", "-C", repoDir.toString()));
    argv.addAll(List.of(args));
    var result = runner.run(Command.of(argv).withEnv(java.util.Map.of("GIT_TERMINAL_PROMPT", "0")));
    if (!result.ok()) {
      var detail = result.tail(20);
      throw new GitException(
          "git " + args[0] + " failed" + (detail.isBlank() ? "" : ": " + detail));
    }
    return result;
  }
}
