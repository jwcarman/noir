package org.jwcarman.noir.cli;

import java.util.concurrent.Callable;
import org.jwcarman.noir.lifecycle.Trigger;
import org.jwcarman.noir.store.InstanceLock;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Cancels a work item. It takes the instance lock: a running daemon may be mid-phase on this item
 * and would overwrite the cancellation when the phase ends. Closing the issue cancels safely while
 * {@code noir run} is active (reconciliation sees it on the next pass).
 */
@Command(name = "cancel", description = "Cancel a work item.")
final class CancelCommand implements Callable<Integer> {
  @ParentCommand NoirCli parent;
  @picocli.CommandLine.Spec CommandSpec spec;

  @Parameters(index = "0", description = "Work item id.")
  String itemId;

  @Override
  public Integer call() {
    var err = spec.commandLine().getErr();
    try (var lock = InstanceLock.tryAcquire(parent.home().lock()).orElse(null)) {
      if (lock == null) {
        err.println("noir run is active; stop it first, or close the issue to cancel");
        err.flush();
        return 2;
      }
      return ItemTriggers.apply(parent, spec, itemId, new Trigger.CancelRequested());
    } catch (java.io.UncheckedIOException e) {
      err.println(e.getMessage());
      err.flush();
      return 2;
    }
  }
}
