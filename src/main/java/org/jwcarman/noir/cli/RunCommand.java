package org.jwcarman.noir.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.jwcarman.noir.factory.Daemon;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.factory.StateChange;
import org.jwcarman.noir.store.InstanceLock;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "run",
    description =
        "Run the server: watch the configured repos, seal issues a human marked noir:ready,"
            + " and drive them to a PR.")
final class RunCommand implements Callable<Integer> {
  @ParentCommand NoirCli parent;
  @picocli.CommandLine.Spec CommandSpec spec;

  @Option(names = "--once", description = "Make one pass over the work, then exit.")
  boolean once;

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    var err = spec.commandLine().getErr();
    var home = parent.home();
    try (var lock = InstanceLock.tryAcquire(home.lock()).orElse(null)) {
      if (lock == null) {
        err.println("Another noir run is already active");
        err.flush();
        return 2;
      }
      parent.runner().run(once, printer(out, err));
      return 0;
    } catch (RuntimeException e) {
      // Every failure is one redacted line; picocli never prints a stack trace for noir run.
      err.println(Secrets.describe(e, parent.secrets()));
      err.flush();
      return 2;
    }
  }

  /** One line per transition on stdout; errors and warnings on stderr. */
  private static Daemon.Listener printer(PrintWriter out, PrintWriter err) {
    return new Daemon.Listener() {
      @Override
      public void started(java.util.List<String> repos, java.time.Duration pollInterval) {
        out.println(startedLine(repos, pollInterval));
        out.flush();
      }

      @Override
      public void transition(StateChange c) {
        out.println(ItemTriggers.line(c));
        out.flush();
      }

      @Override
      public void error(String itemId, String message) {
        err.println(itemId == null ? message : itemId + ": " + message);
        err.flush();
      }

      @Override
      public void warning(String message) {
        err.println(message);
        err.flush();
      }

      @Override
      public void created(String itemId, String repo, int issue) {
        out.println(createdLine(itemId, repo, issue));
        out.flush();
      }
    };
  }

  /** {@code nr-… jwcarman/demo#12 approved → SEALING}. */
  static String createdLine(String itemId, String repo, int issue) {
    return itemId + "  " + repo + "#" + issue + "  approved → SEALING";
  }

  static String startedLine(java.util.List<String> repos, java.time.Duration pollInterval) {
    var watching =
        repos.isEmpty() ? "no repos (add some to config.yaml)" : String.join(", ", repos);
    return "noir running: watching "
        + watching
        + "; polling every "
        + pollInterval.toString().substring(2).toLowerCase(java.util.Locale.ROOT)
        + "; Ctrl-C to stop";
  }
}
