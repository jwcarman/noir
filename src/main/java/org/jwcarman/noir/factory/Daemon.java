package org.jwcarman.noir.factory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.lifecycle.Lifecycle;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * The {@code noir run} loop (spec §6.1, §9.3). Each pass reconciles every item against GitHub, then
 * picks up work — {@code SEALED} items whose open issue in a configured repo is labelled {@code
 * noir:ready}, plus every {@code REWORK} and active item — and drives each one, oldest id first and
 * one at a time, until it is terminal or {@code BLOCKED}.
 *
 * <p>The daemon drives items one {@link ItemDriver#advance} at a time so that it can report every
 * transition to its {@link Listener} without the factory knowing about output.
 *
 * <p>A failure in one item never stops the pass: any {@link RuntimeException} is recorded as a
 * {@code daemon_error} event (secret values removed) and reported. An item that failed while {@code
 * BUILDING}/{@code MEASURING}/{@code GRADING} is then blocked ({@code INFRASTRUCTURE}, resumable
 * with {@code noir resume}) so a persistent fault cannot re-run a paid phase every poll; its issue
 * is labelled {@code noir:blocked} after the state is persisted, best effort. A {@code
 * SEALED}/{@code REWORK} item is left as it is and retried next pass. Errors propagate.
 *
 * <p>A thread interrupt stops the daemon between transitions; the interrupt flag is kept.
 */
public final class Daemon {
  /** Receives what the daemon does. Messages are single-line and free of secret values. */
  public interface Listener {
    void transition(StateChange change);

    /**
     * @param itemId the item the error concerns, or {@code null} for a repo-level failure
     */
    void error(String itemId, String message);

    /** Something the operator should fix that is not an error, such as a skipped item. */
    void warning(String message);
  }

  private static final String READY = "noir:ready";

  private final Store store;
  private final GitHubPort gh;
  private final ContainerPort docker;
  private final ItemDriver driver;
  private final Reconciler reconciler;
  private final NoirConfig config;
  private final Retry.Sleeper sleeper;
  private final Clock clock;
  private final Set<String> secrets;
  private final Listener listener;

  /** Items already warned about, so a polling daemon warns once per item, not once per pass. */
  private final Set<String> warned = new HashSet<>();

  private boolean swept;

  public Daemon(
      Store store,
      GitHubPort gh,
      ContainerPort docker,
      ItemDriver driver,
      Reconciler reconciler,
      NoirConfig config,
      Retry.Sleeper sleeper,
      Clock clock,
      Set<String> secrets,
      Listener listener) {
    this.store = store;
    this.gh = gh;
    this.docker = docker;
    this.driver = driver;
    this.reconciler = reconciler;
    this.config = config;
    this.sleeper = sleeper;
    this.clock = clock;
    this.secrets =
        secrets.stream()
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    this.listener = listener;
  }

  /** Runs passes separated by {@code pollInterval} until the thread is interrupted. */
  public void runForever() {
    while (!interrupted()) {
      runOnce();
      if (interrupted()) {
        return;
      }
      sleeper.sleep(config.pollInterval());
    }
  }

  /**
   * One pass: reconcile, then drive every eligible item. Returns how many items moved. The first
   * pass first removes every container left by an earlier noir that was killed outright (the caller
   * holds the instance lock, so none of them belongs to a live run); a failure to do so propagates,
   * since a leftover builder could still be writing into an attempt directory.
   */
  public int runOnce() {
    if (!swept) {
      docker.removeLabelled(DockerCli.RUN_LABEL);
      swept = true;
    }
    var ids = store.itemIds();
    for (var id : ids) {
      try {
        reconciler.reconcile(id).ifPresent(listener::transition);
      } catch (RuntimeException e) {
        itemError(id, "reconcile", e);
      }
    }
    int advanced = 0;
    for (var item : eligible(ids)) {
      if (interrupted()) {
        break;
      }
      if (drive(item)) {
        advanced++;
      }
    }
    return advanced;
  }

  private List<WorkItem> eligible(List<String> ids) {
    var items = new ArrayList<WorkItem>();
    for (var id : ids) {
      try {
        items.add(store.readWorkItem(id));
      } catch (RuntimeException e) {
        // Already reported by reconciliation, which reads every item first.
      }
    }
    for (var item : items) {
      if (item.state() == State.SEALED
          && !config.repos().contains(item.repo())
          && warned.add(item.id())) {
        listener.warning(
            item.id() + " skipped: repo " + item.repo() + " is not in ~/.noir/config.yaml repos");
      }
    }
    var ready = new HashMap<String, Set<Integer>>();
    for (var repo : config.repos()) {
      if (items.stream().anyMatch(i -> i.state() == State.SEALED && repo.equals(i.repo()))) {
        try {
          ready.put(repo, new HashSet<>(gh.openIssuesWithLabel(repo, READY)));
        } catch (RuntimeException e) {
          listener.error(null, repo + ": " + Secrets.describe(e, secrets));
        }
      }
    }
    return items.stream().filter(i -> eligible(i, ready)).toList();
  }

  private static boolean eligible(WorkItem item, Map<String, Set<Integer>> ready) {
    return switch (item.state()) {
      case SEALED -> ready.getOrDefault(item.repo(), Set.of()).contains(item.issueNumber());
      case REWORK, BUILDING, MEASURING, GRADING -> true;
      default -> false;
    };
  }

  /** Advances until terminal, {@code BLOCKED}, failure or interrupt; true if anything moved. */
  private boolean drive(WorkItem item) {
    boolean moved = false;
    var current = item;
    while (!current.state().terminal() && current.state() != State.BLOCKED && !interrupted()) {
      WorkItem next;
      try {
        next = driver.advance(current.id());
      } catch (RuntimeException e) {
        driveFailed(current.id(), e);
        break;
      }
      listener.transition(new StateChange(current.id(), current.state(), next.state()));
      moved = true;
      current = next;
    }
    return moved;
  }

  /** Records and reports a failure for one item. Best effort: the pass goes on regardless. */
  private void itemError(String id, String during, RuntimeException e) {
    var message = Secrets.describe(e, secrets);
    listener.error(id, message);
    int attempt;
    try {
      attempt = store.readWorkItem(id).attempt();
    } catch (RuntimeException unreadable) {
      attempt = 0;
    }
    try {
      store.appendEvent(
          Event.of(clock, id, "daemon_error", attempt, Map.of("during", during, "error", message)));
    } catch (RuntimeException ignored) {
      // The store itself is failing; the listener already has the message.
    }
  }

  /**
   * After a drive failure, blocks an item whose stored state is active (persist first, then label
   * {@code noir:blocked}); a {@code SEALED}/{@code REWORK} item is left for the next pass.
   */
  private void driveFailed(String id, RuntimeException e) {
    itemError(id, "drive", e);
    WorkItem stored;
    StateChange change;
    try {
      stored = store.readWorkItem(id);
      if (!stored.state().active()) {
        return;
      }
      var trigger = new Trigger.InfraBlocked();
      var next = Lifecycle.apply(stored, trigger, config.reworkRetries()).next();
      change = Transitions.persist(store, clock, stored, next, trigger);
    } catch (RuntimeException failed) {
      listener.error(id, Secrets.describe(failed, secrets));
      return;
    }
    listener.transition(change);
    if (stored.issueNumber() != null) {
      try {
        gh.setNoirLabel(stored.repo(), stored.issueNumber(), State.BLOCKED.label());
      } catch (RuntimeException ignored) {
        // Best effort: reconciliation re-applies noir:blocked on the next pass.
      }
    }
  }

  private static boolean interrupted() {
    return Thread.currentThread().isInterrupted();
  }
}
