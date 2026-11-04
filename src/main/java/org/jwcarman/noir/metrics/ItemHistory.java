package org.jwcarman.noir.metrics;

import java.util.List;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.WorkItem;

/** A work item together with its event log: the unit that experiment metrics fold over. */
public record ItemHistory(WorkItem item, List<Event> events) {
  public ItemHistory {
    events = List.copyOf(events);
  }
}
