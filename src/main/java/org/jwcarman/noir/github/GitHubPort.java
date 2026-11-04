package org.jwcarman.noir.github;

import java.util.List;

/** The GitHub operations noir needs. */
public interface GitHubPort {
  String defaultBranch(String repo);

  /** Creates an issue and returns its number. */
  int createIssue(String repo, String title, String body, List<String> labels);

  void comment(String repo, int issue, String body);

  /** Creates (or updates) every label in {@link NoirLabels#ALL}. */
  void ensureLabels(String repo);
}
