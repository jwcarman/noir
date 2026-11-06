package org.jwcarman.noir.spec;

/**
 * Problem texts for a spec checked against the default branch, shared by the server and desktop.
 */
public final class RepoProblems {
  private RepoProblems() {}

  /** A modify target missing from the default branch. */
  public static String modifyTargetMissing(String path) {
    return "API contract: `" + path + "` (modify) does not exist on the default branch";
  }

  /** A listed test missing from the default branch. */
  public static String testMissing(String path) {
    return "Tests to update: `" + path + "` does not exist on the default branch";
  }

  /** A new-file stub whose path already exists on the default branch. */
  public static String stubExists(String path) {
    return "API contract: `"
        + path
        + "` already exists on the default branch; stubs may only add new files";
  }
}
