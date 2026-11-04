package org.jwcarman.noir.git;

/** A git command failed. */
public class GitException extends RuntimeException {
  public GitException(String message) {
    super(message);
  }
}
