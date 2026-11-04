package org.jwcarman.noir.exec;

import java.util.Arrays;

/** Outcome of a {@link Command}. */
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {

  public boolean ok() {
    return exitCode == 0 && !timedOut;
  }

  /** The last {@code lines} lines of stdout followed by stderr. */
  public String tail(int lines) {
    return tail(stdout + stderr, lines);
  }

  public static String tail(String text, int lines) {
    var all = text.stripTrailing().split("\n", -1);
    return String.join("\n", Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
  }
}
