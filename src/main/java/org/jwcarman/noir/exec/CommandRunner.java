package org.jwcarman.noir.exec;

/** Runs external processes. The single seam through which noir touches git, gh, and docker. */
public interface CommandRunner {
  CommandResult run(Command command);
}
