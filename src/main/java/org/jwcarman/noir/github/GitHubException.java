package org.jwcarman.noir.github;

/** A GitHub operation failed. */
public class GitHubException extends RuntimeException {
  public GitHubException(String message) {
    super(message);
  }
}
