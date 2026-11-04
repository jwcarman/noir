package org.jwcarman.noir.factory;

import java.util.Set;

/** Makes error text safe to log or print: credential values removed, one line, bounded. */
public final class Secrets {
  static final int MAX_LENGTH = 500;
  static final String MASK = "[redacted]";

  private Secrets() {}

  /** {@code text} with every non-blank secret value masked, collapsed to one bounded line. */
  public static String redact(String text, Set<String> secrets) {
    var s = text == null ? "" : text;
    for (var secret : secrets) {
      if (secret != null && !secret.isBlank()) {
        s = s.replace(secret, MASK);
      }
    }
    s = s.replaceAll("\\s+", " ").strip();
    return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) + "…" : s;
  }

  /** The redacted message of {@code e}, or its class name when it has no message. */
  public static String describe(Throwable e, Set<String> secrets) {
    var message = redact(e.getMessage(), secrets);
    return message.isEmpty() ? e.getClass().getSimpleName() : message;
  }
}
