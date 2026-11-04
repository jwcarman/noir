package org.jwcarman.noir.store;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** One line of {@code events.jsonl} (spec §9.3). */
public record Event(
    int v, Instant ts, String itemId, String type, int attempt, Map<String, Object> data) {
  public static final int VERSION = 1;

  public Event {
    data = data == null ? Map.of() : Map.copyOf(data);
  }

  public static Event of(
      Clock clock, String itemId, String type, int attempt, Map<String, Object> data) {
    return new Event(VERSION, clock.instant(), itemId, type, attempt, data);
  }
}
