package org.jwcarman.noir.deliver;

import java.nio.file.Path;
import org.jwcarman.noir.exec.CommandRunner;

/** Test access to {@link GitBranchPublisher}'s remote-URL seam: pushes go to a local origin. */
public final class LocalBranchPublishers {
  private LocalBranchPublishers() {}

  /** A publisher that pushes every repository's branches to the bare repository {@code origin}. */
  public static GitBranchPublisher pushingTo(CommandRunner runner, Path tempRoot, Path origin) {
    var url = origin.toAbsolutePath().toString();
    return new GitBranchPublisher(runner, tempRoot, repo -> url);
  }
}
