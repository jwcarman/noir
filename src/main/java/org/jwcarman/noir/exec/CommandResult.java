package org.jwcarman.noir.exec;

import java.util.Arrays;

/**
 * Outcome of a {@link Command}. {@code stdout} and {@code stderr} are the last {@link
 * Command#outputLimit()} bytes of each stream; {@code truncated} says that earlier output was
 * dropped from at least one of them.
 */
public record CommandResult(
    int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {

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
