package org.jwcarman.noir.factory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.github.NoirLabels;
import org.jwcarman.noir.lifecycle.Lifecycle;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * The {@code noir run} loop (spec §6.1, §9.3). The first pass creates the {@code noir:*} labels in
 * every configured repo (a repo that fails is retried each pass). Each pass reconciles every item
 * against GitHub, records PR dispositions, then discovers approvals: every open issue labelled
 * {@code noir:ready} in a configured repo without a work item gets a new {@code SEALING} item (none
 * while any item is unreadable), and a {@code DRAFT} item whose issue was approved again moves to
 * {@code SEALING}. It then picks up work — {@code SEALED} items in a configured repo, plus every
 * {@code REWORK} and active item ({@code SEALING} included) — and drives each one, oldest id first
 * and one at a time, until it is terminal, {@code BLOCKED} or back in {@code DRAFT}. {@code DRAFT}
 * items are never driven.
 *
 * <p>The daemon drives items one {@link ItemDriver#advance} at a time so that it can report every
 * transition to its {@link Listener} without the factory knowing about output.
 *
 * <p>A failure in one item never stops the pass: any {@link RuntimeException} is recorded as a
 * {@code daemon_error} event (secret values removed) and reported. An item that failed while {@code
 * SEALING}/{@code BUILDING}/{@code MEASURING}/{@code GRADING} is then blocked ({@code
 * INFRASTRUCTURE}, resumable with {@code noir resume}) so a persistent fault cannot re-run a paid
 * phase every poll; its issue is labelled {@code noir:blocked} after the state is persisted, best
 * effort (one that was {@code SEALING} also gets a fixed {@link BlockedNotice} comment). A {@code
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

    /** A work item was created for an approved issue; it starts in {@code SEALING}. */
    void created(String itemId, String repo, int issue);

    /** {@link #runForever()} is starting its poll loop over {@code repos}. */
    default void started(java.util.List<String> repos, java.time.Duration pollInterval) {}

    /**
     * The daemon is picking up an item to drive it from {@code state}; its transitions follow. Sent
     * once per drive, so an item resumed mid-phase (or after a restart) is visible.
     */
    default void working(String itemId, String repo, Integer issue, State state) {}
  }

  private final Store store;
  private final GitHubPort gh;
  private final ContainerPort docker;
  private final ItemDriver driver;
  private final Reconciler reconciler;
  private final Dispositions dispositions;
  private final NoirConfig config;
  private final Retry.Sleeper sleeper;
  private final Clock clock;
  private final RandomGenerator rnd;
  private final Set<String> secrets;
  private final Listener listener;

  /**
   * Items (and disposition warnings, which carry the item id) already reported, so a polling daemon
   * warns once per item, not once per pass.
   */
  private final Set<String> warned = new HashSet<>();

  /** This run's container label; the first pass removes leftovers carrying it. */
  private final String sweepLabel;

  private boolean swept;

  /** Configured repos whose {@code noir:*} labels this run has created (or updated). */
  private final Set<String> labelled = new HashSet<>();

  public Daemon(
      Store store,
      GitHubPort gh,
      ContainerPort docker,
      String sweepLabel,
      ItemDriver driver,
      Reconciler reconciler,
      Dispositions dispositions,
      NoirConfig config,
      Retry.Sleeper sleeper,
      Clock clock,
      RandomGenerator rnd,
      Set<String> secrets,
      Listener listener) {
    this.store = store;
    this.gh = gh;
    this.docker = docker;
    this.sweepLabel = sweepLabel;
    this.driver = driver;
    this.reconciler = reconciler;
    this.dispositions = dispositions;
    this.config = config;
    this.sleeper = sleeper;
    this.clock = clock;
    this.rnd = rnd;
    this.secrets =
        secrets.stream()
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    this.listener = listener;
  }

  /** Runs passes separated by {@code pollInterval} until the thread is interrupted. */
  public void runForever() {
    listener.started(config.repos(), config.pollInterval());
    while (!interrupted()) {
      runOnce();
      if (interrupted()) {
        return;
      }
      sleeper.sleep(config.pollInterval());
    }
  }

  /**
   * One pass: reconcile, record dispositions, discover approvals, then drive every eligible item.
   * Returns how many items moved. The first pass first removes every container left by an earlier
   * noir that was killed outright (the caller holds the instance lock, so none of them belongs to a
   * live run); a failure to do so propagates, since a leftover builder could still be writing into
   * an attempt directory.
   */
  public int runOnce() {
    if (!swept) {
      docker.removeLabelled(sweepLabel);
      swept = true;
    }
    var unlabelled = ensureLabels();
    var ids = store.itemIds();
    for (var id : ids) {
      try {
        reconciler.reconcile(id).ifPresent(listener::transition);
      } catch (RuntimeException e) {
        itemError(id, "reconcile", e);
      }
    }
    refreshDispositions();
    discoverApprovals(unlabelled);
    int advanced = 0;
    for (var item : eligible(store.itemIds())) {
      if (interrupted()) {
        break;
      }
      if (drive(item)) {
        advanced++;
      }
    }
    return advanced;
  }

  /**
   * Creates the {@code noir:*} labels once per configured repo per run, before reconciliation
   * applies any of them. A repo that fails is reported, skipped by this pass's discovery and
   * retried next pass. Returns the repos still without labels.
   */
  private Set<String> ensureLabels() {
    var failed = new HashSet<String>();
    for (var repo : config.repos()) {
      if (labelled.contains(repo)) {
        continue;
      }
      try {
        gh.ensureLabels(repo);
        labelled.add(repo);
      } catch (RuntimeException e) {
        listener.error(null, repo + ": " + Secrets.describe(e, secrets));
        failed.add(repo);
      }
    }
    return failed;
  }

  /**
   * Records PR outcomes; a failure is a warning and never stops the pass. Warnings are redacted and
   * each distinct one is reported once per daemon run, so a permanently failing item does not warn
   * every poll.
   */
  private void refreshDispositions() {
    try {
      dispositions.refresh(this::dispositionWarning);
    } catch (RuntimeException e) {
      dispositionWarning("dispositions: " + Secrets.describe(e, secrets));
    }
  }

  private void dispositionWarning(String message) {
    var redacted = Secrets.redact(message, secrets);
    if (warned.add("pr_disposition " + redacted)) {
      listener.warning(redacted);
    }
  }

  /**
   * Finds every approved issue in each configured repo except {@code skipped} (repos whose labels
   * could not be created); a failing repo never stops the pass. While any work item is unreadable
   * no item is created (its issue cannot be told apart from a new one, and an issue gets one item,
   * ever); existing {@code DRAFT} items are still re-approved.
   */
  private void discoverApprovals(Set<String> skipped) {
    var unreadable = unreadableItems();
    boolean canCreate = unreadable.isEmpty();
    if (!canCreate && warned.add("unreadable " + unreadable)) {
      listener.warning(
          "not creating work items: unreadable work item(s) "
              + unreadable
              + "; fix or remove them");
    }
    for (var repo : config.repos()) {
      if (skipped.contains(repo)) {
        continue;
      }
      List<Integer> ready;
      try {
        ready = gh.openIssuesWithLabel(repo, NoirLabels.READY);
      } catch (RuntimeException e) {
        listener.error(null, repo + ": " + Secrets.describe(e, secrets));
        continue;
      }
      for (int issue : ready) {
        try {
          approved(repo, issue, canCreate);
        } catch (RuntimeException e) {
          listener.error(null, repo + "#" + issue + ": " + Secrets.describe(e, secrets));
        }
      }
    }
  }

  /**
   * Acts on one approved issue: a new item when it has none, {@code DRAFT} back to {@code SEALING},
   * a warning (once) for an item that is already over.
   */
  private void approved(String repo, int issue, boolean canCreate) {
    var ids = store.findItems(repo, issue);
    if (ids.isEmpty()) {
      if (canCreate) {
        create(repo, issue);
      }
      return;
    }
    var id = ids.getFirst();
    if (ids.size() > 1 && warned.add("duplicate " + repo + "#" + issue)) {
      listener.warning(
          repo + "#" + issue + " has more than one work item " + ids + "; using " + id);
    }
    var item = store.readWorkItem(id);
    if (item.state() == State.DRAFT) {
      listener.transition(
          Transitions.applyExternal(
              store,
              clock,
              (i, s) -> gh.setNoirLabel(i.repo(), i.issueNumber(), s.label()),
              item,
              new Trigger.Approved()));
    } else if (item.state().terminal()
        // PR_OPEN is success: its issue keeps noir:ready until the merge closes it.
        && item.state() != State.PR_OPEN
        && warned.add("ready " + id)) {
      listener.warning(
          repo
              + "#"
              + issue
              + " has noir:ready but its work item "
              + id
              + " is "
              + item.state()
              + "; open a new issue");
    }
  }

  private List<String> unreadableItems() {
    var unreadable = new ArrayList<String>();
    for (var id : store.itemIds()) {
      try {
        store.readWorkItem(id);
      } catch (RuntimeException e) {
        unreadable.add(id);
      }
    }
    return unreadable;
  }

  /**
   * Creates a {@code SEALING} item for the issue (persisted first, then labelled {@code
   * noir:sealing} best effort; reconciliation re-applies the label).
   */
  private void create(String repo, int issue) {
    var id = ItemId.newId(clock, rnd);
    var item = WorkItem.approved(id, repo, issue);
    store.writeWorkItem(item);
    store.appendEvent(Event.of(clock, id, "item_created", 0, Map.of("issue", issue)));
    listener.created(id, repo, issue);
    try {
      gh.setNoirLabel(repo, issue, item.state().label());
    } catch (RuntimeException ignored) {
      // Best effort: the next pass's reconciliation labels it.
    }
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
    return items.stream().filter(this::eligible).toList();
  }

  private boolean eligible(WorkItem item) {
    return switch (item.state()) {
      case SEALED -> config.repos().contains(item.repo());
      case REWORK, SEALING, BUILDING, MEASURING, GRADING -> true;
      default -> false;
    };
  }

  /**
   * Advances until terminal, {@code BLOCKED}, {@code DRAFT}, failure or interrupt; true if anything
   * moved.
   */
  private boolean drive(WorkItem item) {
    boolean moved = false;
    var current = item;
    if (Factory.runnable(current.state())) {
      listener.working(current.id(), current.repo(), current.issueNumber(), current.state());
    }
    while (Factory.runnable(current.state()) && !interrupted()) {
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
   * After a drive failure, blocks an item whose stored state is active, {@code SEALING} included
   * (persist first, then label {@code noir:blocked}); a {@code SEALED}/{@code REWORK} item is left
   * for the next pass.
   */
  private void driveFailed(String id, RuntimeException e) {
    itemError(id, "drive", e);
    WorkItem stored;
    WorkItem next;
    StateChange change;
    try {
      stored = store.readWorkItem(id);
      if (!stored.state().active()) {
        return;
      }
      var trigger = new Trigger.InfraBlocked();
      next = Lifecycle.apply(stored, trigger, config.reworkRetries()).next();
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
      if (stored.state() == State.SEALING) {
        BlockedNotice.post(
            store,
            clock,
            next,
            text -> {
              if (secrets.stream().anyMatch(text::contains)) {
                throw new IllegalStateException("secret in outbound comment");
              }
              gh.comment(stored.repo(), stored.issueNumber(), text);
            });
      }
    }
  }

  private static boolean interrupted() {
    return Thread.currentThread().isInterrupted();
  }
}
