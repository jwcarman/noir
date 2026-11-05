package org.jwcarman.noir.github;

import java.util.List;
import java.util.Optional;

/** The GitHub operations noir needs. */
public interface GitHubPort {
  String defaultBranch(String repo);

  /** Creates an issue and returns its number. */
  int createIssue(String repo, String title, String body, List<String> labels);

  void comment(String repo, int issue, String body);

  /** Creates (or updates) every label in {@link NoirLabels#ALL}. */
  void ensureLabels(String repo);

  /** Numbers of open issues carrying {@code label}. */
  List<Integer> openIssuesWithLabel(String repo, String label);

  boolean issueOpen(String repo, int issue);

  /** Reads title, body, open state, edit time and current approval of an issue. */
  IssueSnapshot issue(String repo, int number);

  /** Removes {@code label} from the issue; GitHub does not error when it is absent. */
  void removeLabel(String repo, int number, String label);

  /**
   * Adds {@code label} (if present) and removes every other {@link NoirLabels#STATE} label; {@code
   * noir:ready} is never removed.
   */
  void setNoirLabel(String repo, int issue, Optional<String> label);

  /** Finds a pull request (any state) whose head is {@code headBranch}. */
  Optional<Integer> findPullRequest(String repo, String headBranch);

  /** Creates a pull request and returns its number. */
  int createPullRequest(
      String repo, String base, String head, String title, String body, boolean draft);

  /** The current state of pull request {@code number}. */
  PullRequestState pullRequest(String repo, int number);
}
