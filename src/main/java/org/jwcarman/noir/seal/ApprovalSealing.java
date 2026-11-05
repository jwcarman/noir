package org.jwcarman.noir.seal;

import org.jwcarman.noir.store.WorkItem;

/**
 * The {@code SEALING} phase as the factory sees it: seal an approved issue's work item.
 * Infrastructure exceptions propagate; a rejection is an outcome, not an exception.
 */
@FunctionalInterface
public interface ApprovalSealing {
  SealOutcome seal(WorkItem item);
}
