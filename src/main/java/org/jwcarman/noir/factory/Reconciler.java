package org.jwcarman.noir.factory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * Brings GitHub in line with the store, where {@code workitem.json} wins (spec §9.3). For every
 * non-terminal item: a closed issue cancels the item; otherwise the label of its state is
 * re-applied. Items in {@code SEALING}/{@code BUILDING}/{@code MEASURING}/{@code GRADING} keep
 * their state: the factory re-runs that phase from its stored inputs.
 *
 * <p>An issue edited after its item was sealed gets one comment saying the edits do not change the
 * build: the sealed spec is what noir builds.
 */
public final class Reconciler {
  static final String EDIT_NOTICE =
      "noir: this issue was edited after it was sealed. The edits do not affect the build in"
          + " progress; to change the spec, close this issue and open a new one.";

  private final Store store;
  private final GitHubPort gh;
  private final Delivery delivery;
  private final Clock clock;
  private final Set<String> secrets;

  public Reconciler(
      Store store, GitHubPort gh, Delivery delivery, Clock clock, Set<String> secrets) {
    this.store = store;
    this.gh = gh;
    this.delivery = delivery;
    this.clock = clock;
    this.secrets =
        secrets.stream()
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
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
    var snapshot = gh.issue(item.repo(), item.issueNumber());
    if (!snapshot.open()) {
      return Optional.of(
          Transitions.applyExternal(
              store, clock, delivery::label, item, new Trigger.IssueClosed()));
    }
    delivery.label(item, item.state());
    noticeEditAfterSeal(item, snapshot.editedAt());
    return Optional.empty();
  }

  /**
   * The GitHub edit time of the body that was sealed ({@code sealedEditedAt}), so an edit made
   * while the seal ran counts; items sealed before that key existed fall back to the server time of
   * the {@code sealed} event.
   */
  private static java.time.Instant sealedAt(Event sealed) {
    return sealed.data().get("sealedEditedAt") instanceof String s
        ? java.time.Instant.parse(s)
        : sealed.ts();
  }

  /** Posts {@link #EDIT_NOTICE} once, for an item past sealing whose issue changed since. */
  private void noticeEditAfterSeal(WorkItem item, java.time.Instant editedAt) {
    if (item.state() == State.DRAFT || item.state() == State.SEALING) {
      return;
    }
    // A restarted item was resealed: judge edits against this run's seal only.
    var events = org.jwcarman.noir.store.Runs.latest(store.readEvents(item.id()));
    if (events.stream().anyMatch(e -> e.type().equals("edit_notice"))) {
      return;
    }
    var sealed = events.stream().filter(e -> e.type().equals("sealed")).reduce((a, b) -> b);
    if (sealed.isEmpty() || !editedAt.isAfter(sealedAt(sealed.get()))) {
      return;
    }
    delivery.comment(item, Secrets.mask(EDIT_NOTICE, secrets));
    store.appendEvent(
        Event.of(
            clock,
            item.id(),
            "edit_notice",
            item.attempt(),
            Map.of("editedAt", editedAt.toString())));
  }
}
