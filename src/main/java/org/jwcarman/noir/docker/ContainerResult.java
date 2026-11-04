package org.jwcarman.noir.docker;

import org.jwcarman.noir.exec.CommandResult;

/** Outcome of a container run, with the id of the image it ran. */
public record ContainerResult(
    int exitCode, String stdout, String stderr, boolean timedOut, String imageId) {

  public boolean ok() {
    return exitCode == 0 && !timedOut;
  }

  public String tail(int lines) {
    return CommandResult.tail(stdout + stderr, lines);
  }
}
