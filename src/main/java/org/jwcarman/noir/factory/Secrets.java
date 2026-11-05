package org.jwcarman.noir.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Makes error text safe to log or print: credential values removed, one line, bounded. */
public final class Secrets {
  static final int MAX_LENGTH = 500;
  static final String MASK = "[redacted]";
  static final int MAX_CAUSES = 10;

  private Secrets() {}

  /** {@code text} with every non-blank secret value masked, collapsed to one bounded line. */
  public static String redact(String text, Set<String> secrets) {
    var s = mask(text, secrets).replaceAll("\\s+", " ").strip();
    return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) + "…" : s;
  }

  /**
   * {@code text} with every non-blank secret value masked, formatting kept. For outbound text such
   * as issue comments, where newlines and length matter.
   */
  public static String mask(String text, Set<String> secrets) {
    var s = text == null ? "" : text;
    for (var secret : secrets) {
      if (secret != null && !secret.isBlank()) {
        s = s.replace(secret, MASK);
      }
    }
    return s;
  }

  /**
   * One redacted line describing {@code e}: its message followed by each distinct cause message
   * ({@code "outer: cause"}). Falls back to the class name when no message exists. Null-safe.
   */
  public static String describe(Throwable e, Set<String> secrets) {
    var parts = new ArrayList<String>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (var t = e; t != null && seen.add(t) && seen.size() <= MAX_CAUSES; t = t.getCause()) {
      var m = t.getMessage();
      if (m != null && !m.isBlank() && parts.stream().noneMatch(p -> p.contains(m))) {
        parts.add(m);
      }
    }
    var message = redact(String.join(": ", parts), secrets);
    if (!message.isEmpty()) {
      return message;
    }
    return e == null ? "unknown error" : e.getClass().getSimpleName();
  }
}
