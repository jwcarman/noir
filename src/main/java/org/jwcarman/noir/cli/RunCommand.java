package org.jwcarman.noir.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.exec.ToolMissingException;
import org.jwcarman.noir.factory.Daemon;
import org.jwcarman.noir.factory.InfraFailure;
import org.jwcarman.noir.factory.StateChange;
import org.jwcarman.noir.git.GitException;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.store.InstanceLock;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "run", description = "Run the factory: pick up sealed work and drive it to a PR.")
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
    } catch (ToolMissingException
        | DockerException
        | GitHubException
        | GitException
        | InfraFailure
        | java.io.UncheckedIOException
        | IllegalArgumentException
        | IllegalStateException e) {
      err.println(e.getMessage());
      err.flush();
      return 2;
    }
  }

  /** One line per transition on stdout; per-item errors on stderr. */
  private static Daemon.Listener printer(PrintWriter out, PrintWriter err) {
    return new Daemon.Listener() {
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
    };
  }
}
