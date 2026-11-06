package org.jwcarman.noir.factory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jwcarman.noir.lifecycle.Category;
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
      Set.of(SideEffect.APPLY_LABEL, SideEffect.STOP_CONTAINERS, SideEffect.ARCHIVE_ATTEMPTS);

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
    if (transition.effects().contains(SideEffect.ARCHIVE_ATTEMPTS)) {
      int run = archiveAttempts(store.itemDir(item.id()));
      store.appendEvent(
          Event.of(clock, item.id(), "restarted", item.attempt(), Map.of("run", run)));
    }
    if (transition.effects().contains(SideEffect.APPLY_LABEL)) {
      labeler.accept(next, next.state());
    }
    return persist(store, clock, item, next, trigger);
  }

  /**
   * Moves {@code itemDir/attempts} and {@code itemDir/commitment.json} to {@code
   * itemDir/runs/<n>/}, {@code n} the first unused run number, and returns {@code n}; 0 when there
   * is neither. Without its old commitment the reseal starts from scratch (a committed suite is
   * otherwise reused).
   */
  public static int archiveAttempts(Path itemDir) {
    var attempts = itemDir.resolve("attempts");
    var commitment = itemDir.resolve("commitment.json");
    if (!Files.exists(attempts, LinkOption.NOFOLLOW_LINKS)
        && !Files.exists(commitment, LinkOption.NOFOLLOW_LINKS)) {
      return 0;
    }
    int n = 1;
    while (Files.exists(
        itemDir.resolve("runs").resolve(Integer.toString(n)), LinkOption.NOFOLLOW_LINKS)) {
      n++;
    }
    var run = itemDir.resolve("runs").resolve(Integer.toString(n));
    try {
      Files.createDirectories(run);
      if (Files.exists(attempts, LinkOption.NOFOLLOW_LINKS)) {
        Files.move(attempts, run.resolve("attempts"));
      }
      if (Files.exists(commitment, LinkOption.NOFOLLOW_LINKS)) {
        Files.move(commitment, run.resolve("commitment.json"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return n;
  }

  /** The failure category a trigger implies, if any. */
  static Optional<Category> categoryOf(Trigger trigger) {
    return switch (trigger) {
      case Trigger.MeasurementFailed m -> Optional.of(m.category());
      case Trigger.GradeFailed g -> Optional.of(Category.BEHAVIOR_FAILURE);
      case Trigger.AgentExited a when !a.producedCommits() -> Optional.of(Category.NO_OUTPUT);
      default -> Optional.empty();
    };
  }

  /** Writes {@code next} as {@code workitem.json} and appends its {@code transition} event. */
  static StateChange persist(
      Store store, Clock clock, WorkItem item, WorkItem next, Trigger trigger) {
    store.writeWorkItem(next);
    var data = new LinkedHashMap<String, Object>();
    data.put("from", item.state().name());
    data.put("to", next.state().name());
    data.put("trigger", trigger.getClass().getSimpleName());
    if (next.state() == State.REWORK || next.state() == State.FAILED) {
      categoryOf(trigger).ifPresent(c -> data.put("category", c.name()));
    }
    if (trigger instanceof Trigger.SealRejected r) {
      data.put("reason", r.reason().eventValue());
    }
    store.appendEvent(Event.of(clock, item.id(), "transition", next.attempt(), data));
    return new StateChange(item.id(), item.state(), next.state());
  }
}
