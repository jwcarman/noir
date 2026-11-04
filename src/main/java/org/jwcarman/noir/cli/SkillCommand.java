package org.jwcarman.noir.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;

/** Installs noir's Claude Code skills; today that is the {@code /noir-spec} Specifier (§5.1). */
@Command(
    name = "skill",
    description = "Manage noir's Claude Code skills.",
    subcommands = {SkillCommand.Install.class})
final class SkillCommand implements Runnable {
  static final String NAME = "noir-spec";
  static final String RESOURCE = "/skills/" + NAME + "/SKILL.md";

  @picocli.CommandLine.Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getErr());
  }

  @Command(name = "install", description = "Install the /noir-spec skill for Claude Code.")
  static final class Install implements Callable<Integer> {
    @picocli.CommandLine.Spec CommandSpec spec;

    @Option(
        names = "--dir",
        description = "Claude Code skills directory (default: ~/.claude/skills).")
    Path dir;

    @Override
    public Integer call() {
      var skillsDir =
          dir != null ? dir : Path.of(System.getProperty("user.home"), ".claude", "skills");
      var target = skillsDir.resolve(NAME).resolve("SKILL.md");
      try (InputStream in = SkillCommand.class.getResourceAsStream(RESOURCE)) {
        if (in == null) {
          throw new IOException("missing resource " + RESOURCE);
        }
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        var err = spec.commandLine().getErr();
        err.println("Cannot install skill to " + target + ": " + e);
        err.flush();
        return 1;
      }
      var out = spec.commandLine().getOut();
      out.println("Installed /" + NAME + " skill to " + target);
      out.flush();
      return 0;
    }
  }
}
