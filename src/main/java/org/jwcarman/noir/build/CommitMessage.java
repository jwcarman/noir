package org.jwcarman.noir.build;

/** The one-line message of a builder attempt's commit: the issue title and number. */
public final class CommitMessage {
  /** Titles longer than this many chars are cut (at a code-point boundary) and end with "…". */
  static final int MAX_TITLE = 200;

  private CommitMessage() {}

  /**
   * {@code "<title> (#<issue>)"} for attempt 1 and {@code "<title> (#<issue>), attempt <n>"} after
   * that. Control characters and whitespace runs in the title become one space; a blank title
   * becomes {@code "noir work"}; a null {@code issue} leaves out {@code " (#<issue>)"}.
   */
  public static String of(Integer issue, String title, int attempt) {
    var clean = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    if (clean.isEmpty()) {
      clean = "noir work";
    }
    if (clean.length() > MAX_TITLE) {
      int end = MAX_TITLE;
      if (Character.isHighSurrogate(clean.charAt(end - 1))) {
        end--;
      }
      clean = clean.substring(0, end) + "…";
    }
    var message = issue == null ? clean : clean + " (#" + issue + ")";
    return attempt > 1 ? message + ", attempt " + attempt : message;
  }
}
