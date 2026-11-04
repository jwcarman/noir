package org.jwcarman.noir.cli;

import java.util.concurrent.Callable;
import org.jwcarman.noir.lifecycle.Trigger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Resumes a {@code BLOCKED} work item at the phase that blocked. A running daemon does not drive a
 * blocked item, so this needs no lock: the daemon picks the item up on its next pass.
 */
@Command(name = "resume", description = "Resume a blocked work item.")
final class ResumeCommand implements Callable<Integer> {
  @ParentCommand NoirCli parent;
  @picocli.CommandLine.Spec CommandSpec spec;

  @Parameters(index = "0", description = "Work item id.")
  String itemId;

  @Override
  public Integer call() {
    return ItemTriggers.apply(parent, spec, itemId, new Trigger.ResumeRequested());
  }
}
