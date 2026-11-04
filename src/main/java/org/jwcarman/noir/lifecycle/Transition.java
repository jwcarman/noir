package org.jwcarman.noir.lifecycle;

import java.util.List;
import java.util.Optional;
import org.jwcarman.noir.store.WorkItem;

/** The result of applying a trigger: the next work item, the failure category if any, and side effects to perform. */
public record Transition(WorkItem next, Optional<Category> failure, List<SideEffect> effects) {
    public Transition {
        effects = List.copyOf(effects);
    }
}
