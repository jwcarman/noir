package org.jwcarman.noir.cli;

import java.nio.file.Files;
import java.time.Clock;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.factory.StateChange;
import org.jwcarman.noir.factory.Transitions;
import org.jwcarman.noir.lifecycle.IllegalTransitionException;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.store.Store;
import picocli.CommandLine.Model.CommandSpec;

/** What {@code noir cancel} and {@code noir resume} share: apply one trigger to one item. */
final class ItemTriggers {
  private ItemTriggers() {}

  /** {@code nr-… BUILDING → MEASURING}. */
  static String line(StateChange c) {
    return c.itemId() + " " + c.from() + " → " + c.to();
  }

  /**
   * Applies {@code trigger}, relabels the issue and persists the item. Exit 0 on success, 1 when
   * the item does not exist or the lifecycle refuses, 2 on an infrastructure error.
   */
  static int apply(NoirCli parent, CommandSpec spec, String itemId, Trigger trigger) {
    return apply(parent, spec, itemId, trigger, false);
  }

  /**
   * As {@link #apply(NoirCli, CommandSpec, String, Trigger)}; with {@code requireOpenIssue}, an
   * item whose issue was closed is refused (exit 1) and left for reconciliation to cancel.
   */
  static int apply(
      NoirCli parent, CommandSpec spec, String itemId, Trigger trigger, boolean requireOpenIssue) {
    var out = spec.commandLine().getOut();
    var err = spec.commandLine().getErr();
    var store = new Store(parent.home());
    try {
      if (!ItemId.isValid(itemId)
          || !Files.isRegularFile(store.itemDir(itemId).resolve("workitem.json"))) {
        err.println("No work item " + itemId);
        return 1;
      }
      var item = store.readWorkItem(itemId);
      var gh = parent.github();
      if (requireOpenIssue && !gh.issueOpen(item.repo(), item.issueNumber())) {
        err.println(
            "Issue "
                + item.repo()
                + "#"
                + item.issueNumber()
                + " is closed; not resuming "
                + itemId);
        return 1;
      }
      var change =
          Transitions.applyExternal(
              store,
              Clock.systemUTC(),
              (next, state) -> gh.setNoirLabel(next.repo(), next.issueNumber(), state.label()),
              item,
              trigger);
      out.println(line(change));
      return 0;
    } catch (IllegalTransitionException e) {
      err.println(Secrets.describe(e, parent.secrets()));
      return 1;
    } catch (RuntimeException e) {
      err.println(Secrets.describe(e, parent.secrets()));
      return 2;
    } finally {
      out.flush();
      err.flush();
    }
  }
}
