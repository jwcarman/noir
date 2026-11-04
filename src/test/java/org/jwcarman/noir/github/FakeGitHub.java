package org.jwcarman.noir.github;

import java.util.ArrayList;
import java.util.List;

/** In-memory {@link GitHubPort} for tests. */
public final class FakeGitHub implements GitHubPort {
  public record Issue(
      int number,
      String repo,
      String title,
      String body,
      List<String> labels,
      List<String> comments) {}

  private final List<Issue> issues = new ArrayList<>();
  private final List<String> ensuredRepos = new ArrayList<>();
  private String defaultBranch = "main";
  private boolean failNextCreateIssue;
  private boolean failNextComment;

  public FakeGitHub withDefaultBranch(String branch) {
    this.defaultBranch = branch;
    return this;
  }

  public void failNextCreateIssue() {
    failNextCreateIssue = true;
  }

  public void failNextComment() {
    failNextComment = true;
  }

  public List<Issue> issues() {
    return issues;
  }

  public Issue issue(int number) {
    return issues.stream().filter(i -> i.number() == number).findFirst().orElseThrow();
  }

  public List<String> ensuredRepos() {
    return ensuredRepos;
  }

  @Override
  public String defaultBranch(String repo) {
    return defaultBranch;
  }

  @Override
  public int createIssue(String repo, String title, String body, List<String> labels) {
    if (failNextCreateIssue) {
      failNextCreateIssue = false;
      throw new GitHubException("simulated createIssue failure");
    }
    var issue =
        new Issue(issues.size() + 1, repo, title, body, List.copyOf(labels), new ArrayList<>());
    issues.add(issue);
    return issue.number();
  }

  @Override
  public void comment(String repo, int issue, String body) {
    if (failNextComment) {
      failNextComment = false;
      throw new GitHubException("simulated comment failure");
    }
    issue(issue).comments().add(body);
  }

  @Override
  public void ensureLabels(String repo) {
    ensuredRepos.add(repo);
  }
}
