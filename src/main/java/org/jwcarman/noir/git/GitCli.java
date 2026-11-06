package org.jwcarman.noir.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  @Override
  public void cloneRepo(String repo, Path dir) {
    var result =
        runner.run(
            Command.of(List.of("gh", "repo", "clone", repo, dir.toString(), "--", "--quiet")));
    if (!result.ok()) {
      throw new GitException("gh repo clone failed: " + result.tail(20));
    }
  }

  @Override
  public boolean exists(Path repoDir, String sha, String path) {
    var result =
        runner.run(
            Command.of(List.of("git", "-C", repoDir.toString(), "cat-file", "-e", sha + ":" + path))
                .withEnv(java.util.Map.of("GIT_TERMINAL_PROMPT", "0")));
    if (result.ok()) {
      return true;
    }
    if (result.exitCode() == 128 && !result.timedOut()) {
      return false;
    }
    throw new GitException("git cat-file failed: " + result.tail(20));
  }

  @Override
  public Optional<String> show(Path repoDir, String rev, String path) {
    var env = java.util.Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C");
    var resolved =
        runner.run(
            Command.of(
                    List.of("git", "-C", repoDir.toString(), "cat-file", "-e", rev + "^{commit}"))
                .withEnv(env));
    if (!resolved.ok()) {
      throw new GitException("git show failed: cannot resolve " + rev + ": " + resolved.tail(20));
    }
    var result =
        runner.run(
            Command.of(List.of("git", "-C", repoDir.toString(), "show", rev + ":" + path))
                .withEnv(env));
    if (result.ok()) {
      if (result.truncated()) {
        throw new GitException("git show output for " + path + " was truncated");
      }
      return Optional.of(result.stdout());
    }
    if (result.exitCode() == 128
        && !result.timedOut()
        && (result.stderr().contains("does not exist in")
            || result.stderr().contains("exists on disk, but not in"))) {
      return Optional.empty();
    }
    throw new GitException("git show failed: " + result.tail(20));
  }

  @Override
  public List<String> listFiles(Path repoDir, String rev, String pathPrefix) {
    var env = java.util.Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C");
    var resolved =
        runner.run(
            Command.of(
                    List.of("git", "-C", repoDir.toString(), "cat-file", "-e", rev + "^{commit}"))
                .withEnv(env));
    if (!resolved.ok()) {
      throw new GitException(
          "git ls-tree failed: cannot resolve " + rev + ": " + resolved.tail(20));
    }
    var result =
        runner.run(
            Command.of(
                    List.of(
                        "git",
                        "-C",
                        repoDir.toString(),
                        "ls-tree",
                        "-r",
                        "-z",
                        "--name-only",
                        "--full-tree",
                        rev,
                        "--",
                        pathPrefix))
                .withEnv(env));
    if (!result.ok()) {
      throw new GitException("git ls-tree failed: " + result.tail(20));
    }
    if (result.truncated()) {
      throw new GitException("git ls-tree output for " + pathPrefix + " was truncated");
    }
    return java.util.Arrays.stream(result.stdout().split("\0")).filter(p -> !p.isEmpty()).toList();
  }

  @Override
  public String diffNoIndex(Path dir, String oldPath, String newPath) {
    var result =
        runner.run(
            Command.of(
                    List.of(
                        "git",
                        "-C",
                        dir.toString(),
                        "diff",
                        "--no-index",
                        "--no-color",
                        "--no-ext-diff",
                        "--no-textconv",
                        // A CRLF base against an LF reference is not a change on every line.
                        "--ignore-cr-at-eol",
                        "-U0",
                        "--src-prefix=a/",
                        "--dst-prefix=b/",
                        "--",
                        oldPath,
                        newPath))
                .withEnv(java.util.Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C")));
    // Exit 1 means "the files differ", but git also exits 1, printing no diff, when it cannot
    // read a file.
    var differs = result.exitCode() == 1 && !result.stdout().isBlank();
    if (result.timedOut() || result.truncated() || !(result.ok() || differs)) {
      throw new GitException("git diff --no-index failed: " + result.tail(20));
    }
    return result.stdout();
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
