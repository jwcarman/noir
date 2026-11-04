package org.jwcarman.noir.cli;

import org.jwcarman.noir.factory.Daemon;

/** Runs the daemon. The seam between {@link RunCommand} and {@link Wiring}. */
@FunctionalInterface
interface RunOperation {
  /**
   * @param once one pass, else poll until interrupted
   * @param listener receives every transition and per-item error
   */
  void run(boolean once, Daemon.Listener listener);
}
