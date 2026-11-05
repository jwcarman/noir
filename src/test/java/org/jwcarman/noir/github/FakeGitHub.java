package org.jwcarman.noir.github;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** In-memory {@link GitHubPort} for tests. */
public final class FakeGitHub implements GitHubPort {
  public record Issue(
      int number,
      String repo,
      String title,
      String body,
      List<String> labels,
      List<String> comments,
      boolean open) {}

  public record Pr(
      int number, String base, String head, String title, String body, boolean draft) {}

  private final List<Issue> issues = new ArrayList<>();
  private final List<String> ensuredRepos = new ArrayList<>();
  private final List<Pr> pullRequests = new ArrayList<>();
  private final Map<Integer, PullRequestState> prStates = new HashMap<>();

  private record ReadyEvent(String actor, Instant at) {}

  private final Map<Integer, Instant> editedAt = new HashMap<>();
  private final Map<Integer, Instant> createdAt = new HashMap<>();
  private final Map<Integer, List<ReadyEvent>> readyEvents = new HashMap<>();
  private boolean failNextCreatePullRequest;
  private String defaultBranch = "main";
  private boolean failNextCreateIssue;
  private boolean failNextComment;
  private boolean failNextSetNoirLabel;
  private String pullRequestFailure;
  private RuntimeException issueFailure;
  private int issueFailures;

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

  public void failNextSetNoirLabel() {
    failNextSetNoirLabel = true;
  }

  public void failNextCreatePullRequest() {
    failNextCreatePullRequest = true;
  }

  /**
   * Every later {@link #pullRequest} call throws a {@link GitHubException} with {@code message}.
   */
  public void failPullRequests(String message) {
    pullRequestFailure = message;
  }

  /** The next {@code times} {@link #issue(String, int)} reads throw {@code failure}. */
  public void failIssueReads(int times, RuntimeException failure) {
    issueFailures = times;
    issueFailure = failure;
  }

  public List<Pr> pullRequests() {
    return pullRequests;
  }

  public void closeIssue(int number) {
    replace(issue(number), i -> withOpen(i, false));
  }

  private static Issue withOpen(Issue i, boolean open) {
    return new Issue(i.number(), i.repo(), i.title(), i.body(), i.labels(), i.comments(), open);
  }

  private void replace(Issue old, java.util.function.UnaryOperator<Issue> f) {
    issues.set(issues.indexOf(old), f.apply(old));
  }

  /** Sets the time the issue was created (default {@link Instant#EPOCH}). */
  public void createdAt(int n, Instant at) {
    createdAt.put(n, at);
  }

  /** Edits title and body, recording {@code at} as the edit time. */
  public void editIssue(int n, String title, String body, Instant at) {
    replace(
        issue(n),
        i -> new Issue(i.number(), i.repo(), title, body, i.labels(), i.comments(), i.open()));
    editedAt.put(n, at);
  }

  /** Adds a label as {@code actor} at {@code at}; adding {@code noir:ready} records an approval. */
  public void addLabel(int n, String label, String actor, Instant at) {
    var labels = issue(n).labels();
    if (!labels.contains(label)) {
      labels.add(label);
    }
    if (label.equals(NoirLabels.READY)) {
      readyEvents.computeIfAbsent(n, k -> new ArrayList<>()).add(new ReadyEvent(actor, at));
    }
  }

  @Override
  public void removeLabel(String repo, int number, String label) {
    issue(number).labels().remove(label);
  }

  public Set<String> labels(int n) {
    return new TreeSet<>(issue(n).labels());
  }

  public List<String> comments(int n) {
    return issue(n).comments();
  }

  @Override
  public IssueSnapshot issue(String repo, int number) {
    if (issueFailures > 0) {
      issueFailures--;
      throw issueFailure;
    }
    var i = issue(number);
    var created = createdAt.getOrDefault(number, Instant.EPOCH);
    var edited = editedAt.getOrDefault(number, created);
    if (edited.isBefore(created)) {
      edited = created;
    }
    var approval =
        i.labels().contains(NoirLabels.READY)
            ? readyEvents.getOrDefault(number, List.of()).stream()
                .reduce((a, b) -> b.at().isBefore(a.at()) ? a : b)
                .map(e -> new IssueSnapshot.Approval(e.actor(), e.at()))
            : Optional.<IssueSnapshot.Approval>empty();
    return new IssueSnapshot(
        number, i.title(), i.body() == null ? "" : i.body(), i.open(), created, edited, approval);
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
        new Issue(
            issues.size() + 1, repo, title, body, new ArrayList<>(labels), new ArrayList<>(), true);
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

  @Override
  public List<Integer> openIssuesWithLabel(String repo, String label) {
    return issues.stream()
        .filter(i -> i.repo().equals(repo) && i.open() && i.labels().contains(label))
        .map(Issue::number)
        .toList();
  }

  @Override
  public boolean issueOpen(String repo, int issue) {
    return issue(issue).open();
  }

  @Override
  public void setNoirLabel(String repo, int issue, Optional<String> label) {
    if (failNextSetNoirLabel) {
      failNextSetNoirLabel = false;
      throw new GitHubException("simulated setNoirLabel failure");
    }
    var labels = issue(issue).labels();
    NoirLabels.STATE.forEach(l -> labels.remove(l.name()));
    label.filter(l -> !labels.contains(l)).ifPresent(labels::add);
  }

  @Override
  public Optional<Integer> findPullRequest(String repo, String headBranch) {
    return pullRequests.stream()
        .filter(p -> p.head().equals(headBranch))
        .map(Pr::number)
        .findFirst();
  }

  @Override
  public int createPullRequest(
      String repo, String base, String head, String title, String body, boolean draft) {
    if (failNextCreatePullRequest) {
      failNextCreatePullRequest = false;
      throw new GitHubException("simulated createPullRequest failure");
    }
    var pr = new Pr(pullRequests.size() + 1, base, head, title, body, draft);
    pullRequests.add(pr);
    prStates.put(
        pr.number(),
        new PullRequestState(pr.number(), PullRequestState.Status.OPEN, "fake-head", null));
    return pr.number();
  }

  @Override
  public PullRequestState pullRequest(String repo, int number) {
    if (pullRequestFailure != null) {
      throw new GitHubException(pullRequestFailure);
    }
    var state = prStates.get(number);
    if (state == null) {
      throw new GitHubException("no such pull request: " + number);
    }
    return state;
  }

  /** Marks the pull request merged with {@code headSha} as its head. */
  public void merge(int pr, String headSha) {
    prStates.put(
        pr,
        new PullRequestState(pr, PullRequestState.Status.MERGED, headSha, java.time.Instant.EPOCH));
  }

  public void closePullRequest(int pr) {
    var old = pullRequest("", pr);
    prStates.put(pr, new PullRequestState(pr, PullRequestState.Status.CLOSED, old.headSha(), null));
  }
}
