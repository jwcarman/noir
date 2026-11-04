package org.jwcarman.noir.deliver;

import java.nio.file.Path;

/** Publishes a branch from a git bundle to the remote repository. */
public interface BranchPublisher {
  void push(String repo, Path bundle, String branch);
}
