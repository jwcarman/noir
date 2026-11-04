package org.jwcarman.noir.factory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jwcarman.noir.lifecycle.IllegalTransitionException;
import org.jwcarman.noir.lifecycle.Lifecycle;
import org.jwcarman.noir.lifecycle.SideEffect;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * Applies a trigger that comes from outside the factory ({@code IssueClosed}, {@code
 * CancelRequested}, {@code ResumeRequested}): these only relabel the issue and persist the state.
 *
 * <p>The label is applied first, then {@code workitem.json} and a {@code transition} event (the
 * same shape {@link Factory} writes). If labelling fails nothing is persisted; if persisting fails
 * reconciliation restores the label of the stored state on the next pass.
 */
public final class Transitions {
  /** Effects an external trigger may carry; containers are --rm and phase-scoped. */
  private static final Set<SideEffect> HANDLED =
      Set.of(SideEffect.APPLY_LABEL, SideEffect.STOP_CONTAINERS);

  private Transitions() {}

  /**
   * @param labeler mirrors a state as the issue's label, e.g. {@link Delivery#label}
   * @throws IllegalTransitionException if the trigger is not valid in the item's state
   */
  public static StateChange applyExternal(
      Store store,
      Clock clock,
      BiConsumer<WorkItem, State> labeler,
      WorkItem item,
      Trigger trigger) {
    // maxReworks only matters for failure triggers, which never come from outside the factory.
    var transition = Lifecycle.apply(item, trigger, 0);
    if (!HANDLED.containsAll(transition.effects())) {
      throw new IllegalArgumentException(
          "Not an external trigger: " + trigger.getClass().getSimpleName());
    }
    var next = transition.next();
    if (transition.effects().contains(SideEffect.APPLY_LABEL)) {
      labeler.accept(next, next.state());
    }
    store.writeWorkItem(next);
    var data = new LinkedHashMap<String, Object>();
    data.put("from", item.state().name());
    data.put("to", next.state().name());
    data.put("trigger", trigger.getClass().getSimpleName());
    store.appendEvent(Event.of(clock, item.id(), "transition", next.attempt(), data));
    return new StateChange(item.id(), item.state(), next.state());
  }
}
