package org.jwcarman.noir.github;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.CommandRunner;
import org.jwcarman.noir.json.Json;

/** {@link GitHubPort} backed by the {@code gh} CLI. Bodies are always passed on stdin. */
public final class GhCli implements GitHubPort {
  private static final Pattern ISSUE_URL = Pattern.compile("/issues/(\\d+)\\s*$");

  private static final Pattern PULL_URL = Pattern.compile("/pull/(\\d+)\\s*$");

  private static final String ISSUE_QUERY =
      "query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){issue(number:$number){"
          + " number title body state createdAt lastEditedAt labels(first:100){nodes{name}}"
          + " timelineItems(last:100,itemTypes:[LABELED_EVENT,RENAMED_TITLE_EVENT]){nodes{"
          + " __typename ... on LabeledEvent{createdAt actor{login} label{name}} ... on RenamedTitleEvent{createdAt}}}}}}";

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

  @Override
  public List<Integer> openIssuesWithLabel(String repo, String label) {
    var out =
        gh(
                null,
                "issue",
                "list",
                "--repo",
                repo,
                "--label",
                label,
                "--state",
                "open",
                "--json",
                "number",
                "--jq",
                ".[].number",
                "--limit",
                "200")
            .stdout();
    return out.lines().map(String::trim).filter(l -> !l.isEmpty()).map(Integer::parseInt).toList();
  }

  @Override
  public boolean issueOpen(String repo, int issue) {
    var out =
        gh(
            null,
            "issue",
            "view",
            String.valueOf(issue),
            "--repo",
            repo,
            "--json",
            "state",
            "--jq",
            ".state");
    return out.stdout().trim().equals("OPEN");
  }

  @Override
  public void setNoirLabel(String repo, int issue, Optional<String> label) {
    var args = new ArrayList<>(List.of("issue", "edit", String.valueOf(issue), "--repo", repo));
    label.ifPresent(l -> {
      args.add("--add-label");
      args.add(l);
    });
    args.add("--remove-label");
    args.add(
        NoirLabels.STATE.stream()
            .map(NoirLabel::name)
            .filter(n -> label.map(l -> !l.equals(n)).orElse(true))
            .collect(Collectors.joining(",")));
    gh(null, args.toArray(String[]::new));
  }

  @Override
  public void removeLabel(String repo, int number, String label) {
    gh(null, "issue", "edit", String.valueOf(number), "--repo", repo, "--remove-label", label);
  }

  @Override
  public IssueSnapshot issue(String repo, int number) {
    var slash = repo.indexOf('/');
    var out =
        gh(
                null,
                "api",
                "graphql",
                "-f",
                "query=" + ISSUE_QUERY,
                "-F",
                "owner=" + repo.substring(0, slash),
                "-F",
                "name=" + repo.substring(slash + 1),
                "-F",
                "number=" + number)
            .stdout();
    try {
      var issue = Json.mapper().readTree(out).path("data").path("repository").path("issue");
      if (!issue.isObject()) {
        throw new GitHubException("issue " + repo + "#" + number + " not found");
      }
      var createdAt = Instant.parse(issue.required("createdAt").asText());
      var editedAt = createdAt;
      var lastEdited = issue.path("lastEditedAt");
      if (lastEdited.isTextual()) {
        editedAt = later(editedAt, Instant.parse(lastEdited.asText()));
      }
      boolean readyPresent = false;
      for (var l : issue.path("labels").path("nodes")) {
        readyPresent |= NoirLabels.READY.equals(l.path("name").asText());
      }
      IssueSnapshot.Approval approval = null;
      for (var e : issue.path("timelineItems").path("nodes")) {
        var at = Instant.parse(e.required("createdAt").asText());
        var type = e.path("__typename").asText();
        if (type.equals("RenamedTitleEvent")) {
          editedAt = later(editedAt, at);
        } else if (type.equals("LabeledEvent")
            && NoirLabels.READY.equals(e.path("label").path("name").asText())
            && (approval == null || !at.isBefore(approval.at()))) {
          var login = e.path("actor").path("login");
          approval = new IssueSnapshot.Approval(login.isTextual() ? login.asText() : "ghost", at);
        }
      }
      var body = issue.path("body");
      return new IssueSnapshot(
          issue.required("number").intValue(),
          issue.required("title").asText(),
          body.isTextual() ? body.asText() : "",
          issue.required("state").asText().equals("OPEN"),
          createdAt,
          editedAt,
          readyPresent ? Optional.ofNullable(approval) : Optional.empty());
    } catch (IOException | RuntimeException e) {
      if (e instanceof GitHubException g) {
        throw g;
      }
      throw new GitHubException("gh api graphql returned unexpected output: " + out.trim());
    }
  }

  private static Instant later(Instant a, Instant b) {
    return b.isAfter(a) ? b : a;
  }

  @Override
  public Optional<Integer> findPullRequest(String repo, String headBranch) {
    var out =
        gh(
                null,
                "pr",
                "list",
                "--repo",
                repo,
                "--head",
                headBranch,
                "--state",
                "all",
                "--json",
                "number",
                "--jq",
                ".[0].number")
            .stdout()
            .trim();
    if (out.isEmpty() || out.equals("null")) {
      return Optional.empty();
    }
    return Optional.of(Integer.parseInt(out));
  }

  @Override
  public int createPullRequest(
      String repo, String base, String head, String title, String body, boolean draft) {
    var args =
        new ArrayList<>(
            List.of(
                "pr",
                "create",
                "--repo",
                repo,
                "--base",
                base,
                "--head",
                head,
                "--title",
                title,
                "--body-file",
                "-"));
    if (draft) {
      args.add("--draft");
    }
    var out = gh(body, args.toArray(String[]::new)).stdout().trim();
    var m = PULL_URL.matcher(out);
    if (!m.find()) {
      throw new GitHubException("gh pr create returned no pull request URL: " + out);
    }
    return Integer.parseInt(m.group(1));
  }

  @Override
  public PullRequestState pullRequest(String repo, int number) {
    var out =
        gh(
                null,
                "pr",
                "view",
                String.valueOf(number),
                "--repo",
                repo,
                "--json",
                "number,state,headRefOid,mergedAt")
            .stdout();
    try {
      var json = Json.mapper().readTree(out);
      var mergedAt = json.path("mergedAt");
      var head = json.required("headRefOid");
      if (!head.isTextual() || head.asText().isBlank()) {
        throw new GitHubException("gh pr view returned no head commit: " + out.trim());
      }
      return new PullRequestState(
          json.required("number").intValue(),
          PullRequestState.Status.valueOf(json.required("state").asText()),
          head.asText(),
          mergedAt.isNull() || mergedAt.isMissingNode() ? null : Instant.parse(mergedAt.asText()));
    } catch (IOException | RuntimeException e) {
      throw new GitHubException("gh pr view returned unexpected output: " + out.trim());
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
