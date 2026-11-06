package org.jwcarman.noir.store;

import java.util.List;

/**
 * An item that went back to draft after building restarts on re-approval (a {@code restarted}
 * event) and reuses attempt numbers and seal events. Readers that mean "this run" use {@link
 * #latest}.
 */
public final class Runs {
  private Runs() {}

  /** The events after the last {@code restarted} event; all of them when there is none. */
  public static List<Event> latest(List<Event> events) {
    int from = 0;
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).type().equals("restarted")) {
        from = i + 1;
      }
    }
    return events.subList(from, events.size());
  }
}
