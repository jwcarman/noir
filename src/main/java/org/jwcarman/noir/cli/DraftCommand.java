package org.jwcarman.noir.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jwcarman.noir.config.ConfigLoader;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.spec.DraftCheck;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "draft",
    description = "Work with drafts (unsealed work items).",
    subcommands = {
      DraftCommand.Init.class,
      DraftCommand.ListDrafts.class,
      DraftCommand.Check.class,
      DraftCommand.Show.class
    })
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
      new Store(home)
          .appendEvent(
              dir,
              Event.of(
                  Clock.systemUTC(), id, "draft_created", 0, Map.of("repo", repo, "title", title)));
      var out = spec.commandLine().getOut();
      out.println("Created draft " + id);
      out.println("  " + dir);
      out.flush();
      return 0;
    }
  }

  @Command(name = "list", description = "List drafts.")
  static final class ListDrafts implements Callable<Integer> {
    @ParentCommand DraftCommand draft;
    @picocli.CommandLine.Spec CommandSpec spec;

    @Override
    public Integer call() {
      var out = spec.commandLine().getOut();
      var root = draft.parent.home().drafts();
      var lines = new java.util.ArrayList<String>();
      if (Files.isDirectory(root)) {
        try (var dirs = Files.list(root)) {
          dirs.filter(d -> Files.isRegularFile(d.resolve("spec.json")))
              .sorted()
              .forEach(d -> {
                try {
                  var s = SpecJson.read(d.resolve("spec.json"));
                  lines.add(d.getFileName() + "  " + s.repo() + "  " + s.title());
                } catch (RuntimeException e) {
                  lines.add(d.getFileName() + "  (unreadable spec.json)");
                }
              });
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      if (lines.isEmpty()) {
        out.println("No drafts.");
      } else {
        lines.forEach(out::println);
      }
      out.flush();
      return 0;
    }
  }

  @Command(name = "check", description = "Check a draft for problems before sealing.")
  static final class Check implements Callable<Integer> {
    @ParentCommand DraftCommand draft;
    @picocli.CommandLine.Spec CommandSpec spec;

    @Parameters(index = "0", description = "Draft id.")
    String draftId;

    @Override
    public Integer call() {
      var dir = existingDraft(draft, spec, draftId);
      if (dir == null) {
        return 1;
      }
      var out = spec.commandLine().getOut();
      var problems = DraftCheck.check(dir);
      if (problems.isEmpty()) {
        out.println("Draft " + draftId + " OK");
      } else {
        problems.forEach(out::println);
      }
      out.flush();
      return problems.isEmpty() ? 0 : 1;
    }
  }

  @Command(name = "show", description = "Print the issue text a draft would produce.")
  static final class Show implements Callable<Integer> {
    @ParentCommand DraftCommand draft;
    @picocli.CommandLine.Spec CommandSpec spec;

    @Parameters(index = "0", description = "Draft id.")
    String draftId;

    @Override
    public Integer call() {
      var dir = existingDraft(draft, spec, draftId);
      if (dir == null) {
        return 1;
      }
      var err = spec.commandLine().getErr();
      try {
        var s = SpecJson.read(dir.resolve("spec.json"));
        var out = spec.commandLine().getOut();
        out.println(SpecRenderer.issueBody(s, SpecRenderer.loadStubs(s, dir)));
        out.flush();
        return 0;
      } catch (RuntimeException e) {
        err.println("Cannot render draft " + draftId + ": " + e.getMessage());
        err.flush();
        return 1;
      }
    }
  }

  private static java.nio.file.Path existingDraft(
      DraftCommand draft, CommandSpec spec, String draftId) {
    if (!ItemId.isValid(draftId)) {
      return fail(spec, "Invalid draft id " + draftId);
    }
    var dir = new Store(draft.parent.home()).draftDir(draftId);
    if (!Files.isRegularFile(dir.resolve("spec.json"))) {
      return fail(spec, "No draft " + draftId);
    }
    return dir;
  }

  private static java.nio.file.Path fail(CommandSpec spec, String message) {
    var err = spec.commandLine().getErr();
    err.println(message);
    err.flush();
    return null;
  }
}
