package org.jwcarman.noir.factory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.store.Store;

/**
 * Brings GitHub in line with the store, where {@code workitem.json} wins (spec §9.3). For every
 * non-terminal item: a closed issue cancels the item; otherwise the label of its state is
 * re-applied. Items in {@code BUILDING}/{@code MEASURING}/{@code GRADING} keep their state: the
 * factory re-runs that phase from its stored inputs.
 */
public final class Reconciler {
  private final Store store;
  private final GitHubPort gh;
  private final Delivery delivery;
  private final Clock clock;

  public Reconciler(Store store, GitHubPort gh, Delivery delivery, Clock clock) {
    this.store = store;
    this.gh = gh;
    this.delivery = delivery;
    this.clock = clock;
  }

  /** Reconciles every item; the first failure propagates. Returns the transitions made. */
  public List<StateChange> reconcile() {
    var changes = new ArrayList<StateChange>();
    for (var id : store.itemIds()) {
      reconcile(id).ifPresent(changes::add);
    }
    return changes;
  }

  /** Reconciles one item; returns its transition if its issue was closed. */
  public Optional<StateChange> reconcile(String itemId) {
    var item = store.readWorkItem(itemId);
    if (item.state().terminal() || item.issueNumber() == null) {
      return Optional.empty();
    }
    if (!gh.issueOpen(item.repo(), item.issueNumber())) {
      return Optional.of(
          Transitions.applyExternal(
              store, clock, delivery::label, item, new Trigger.IssueClosed()));
    }
    delivery.label(item, item.state());
    return Optional.empty();
  }
}
