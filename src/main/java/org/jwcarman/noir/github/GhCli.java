package org.jwcarman.noir.github;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.CommandRunner;

/** {@link GitHubPort} backed by the {@code gh} CLI. Bodies are always passed on stdin. */
public final class GhCli implements GitHubPort {
  private static final Pattern ISSUE_URL = Pattern.compile("/issues/(\\d+)\\s*$");

  private final CommandRunner runner;

  public GhCli(CommandRunner runner) {
    this.runner = runner;
  }

  @Override
  public String defaultBranch(String repo) {
    return gh(null, "api", "repos/" + repo, "--jq", ".default_branch").stdout().trim();
  }

  @Override
  public int createIssue(String repo, String title, String body, List<String> labels) {
    var args =
        new ArrayList<>(
            List.of("issue", "create", "--repo", repo, "--title", title, "--body-file", "-"));
    for (var label : labels) {
      args.add("--label");
      args.add(label);
    }
    var out = gh(body, args.toArray(String[]::new)).stdout().trim();
    var m = ISSUE_URL.matcher(out);
    if (!m.find()) {
      throw new GitHubException("gh issue create returned no issue URL: " + out);
    }
    return Integer.parseInt(m.group(1));
  }

  @Override
  public void comment(String repo, int issue, String body) {
    gh(body, "issue", "comment", String.valueOf(issue), "--repo", repo, "--body-file", "-");
  }

  @Override
  public void ensureLabels(String repo) {
    for (var label : NoirLabels.ALL) {
      gh(
          null,
          "label",
          "create",
          label.name(),
          "--repo",
          repo,
          "--color",
          label.color(),
          "--description",
          label.description(),
          "--force");
    }
  }

  private CommandResult gh(String stdin, String... args) {
    var argv = new ArrayList<String>();
    argv.add("gh");
    argv.addAll(List.of(args));
    var command = Command.of(argv);
    if (stdin != null) {
      command = command.withStdin(stdin);
    }
    var result = runner.run(command);
    if (!result.ok()) {
      if (result.stderr().contains("gh auth login")) {
        throw new GitHubException("GitHub CLI is not authenticated; run: gh auth login");
      }
      throw new GitHubException("gh " + args[0] + " failed: " + result.tail(20));
    }
    return result;
  }
}
