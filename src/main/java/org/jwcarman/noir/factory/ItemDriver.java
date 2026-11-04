package org.jwcarman.noir.factory;

import org.jwcarman.noir.store.WorkItem;

/** Moves a work item forward by one transition. {@link Factory} is the production driver. */
@FunctionalInterface
public interface ItemDriver {
  /** Performs exactly one transition and returns the new work item. */
  WorkItem advance(String itemId);
}
