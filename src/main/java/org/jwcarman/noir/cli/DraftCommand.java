package org.jwcarman.noir.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import org.jwcarman.noir.config.ConfigLoader;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.store.Store;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "draft",
    description = "Work with drafts (unsealed work items).",
    subcommands = {DraftCommand.Init.class})
final class DraftCommand implements Runnable {
  @ParentCommand NoirCli parent;
  @picocli.CommandLine.Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getErr());
  }

  @Command(name = "init", description = "Create a draft skeleton to fill in by hand.")
  static final class Init implements Callable<Integer> {
    @ParentCommand DraftCommand draft;
    @picocli.CommandLine.Spec CommandSpec spec;

    @Option(names = "--repo", required = true, description = "Target repository (owner/name).")
    String repo;

    @Option(names = "--title", required = true, description = "Work item title.")
    String title;

    @Override
    public Integer call() {
      var home = draft.parent.home();
      var config = ConfigLoader.load(home.config());
      var id = ItemId.newId(Clock.systemUTC(), new SecureRandom());
      var todo = new Example("TODO", "TODO", "TODO");
      var skeleton =
          new Spec(
              1,
              id,
              repo,
              null,
              title,
              "TODO",
              List.of(new Requirement("R1", "TODO", List.of(todo))),
              List.of(),
              config.qualityBar(),
              List.of());
      var dir = new Store(home).draftDir(id);
      try {
        Files.createDirectories(dir.resolve("stubs"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      SpecJson.write(skeleton, dir.resolve("spec.json"));
      var out = spec.commandLine().getOut();
      out.println("Created draft " + id);
      out.println("  " + dir);
      out.flush();
      return 0;
    }
  }
}
