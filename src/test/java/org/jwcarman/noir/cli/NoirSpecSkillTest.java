package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.NoirHome;
import picocli.CommandLine;

/** Checks the packaged {@code /noir-spec} skill against the real CLI and the specifier rules. */
class NoirSpecSkillTest {
  private static final Pattern INLINE = Pattern.compile("`(noir [^`]+)`");
  private static final Pattern PLACEHOLDER = Pattern.compile("<[^<>]+>");
  private static final Pattern WRITE_INTO_SRC =
      Pattern.compile(
          "(?i)\\b(write|writes|writing|create|creates|save|put|edit|modify|add)\\b.*(^|[\\s`(./])src/");

  static List<String> noirCommands(String skill) {
    var commands = new LinkedHashSet<String>();
    boolean fenced = false;
    for (var line : skill.lines().toList()) {
      if (line.strip().startsWith("```")) {
        fenced = !fenced;
        continue;
      }
      if (fenced) {
        if (line.strip().startsWith("noir ")) {
          commands.add(line.strip());
        }
      } else {
        var m = INLINE.matcher(line);
        while (m.find()) {
          commands.add(m.group(1).strip());
        }
      }
    }
    return List.copyOf(commands);
  }

  static List<String> tokenize(String command) {
    var tokens = new ArrayList<String>();
    var current = new StringBuilder();
    boolean quoted = false;
    boolean inToken = false;
    for (char c : command.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
        inToken = true;
      } else if (Character.isWhitespace(c) && !quoted) {
        if (inToken) {
          tokens.add(current.toString());
          current.setLength(0);
          inToken = false;
        }
      } else {
        current.append(c);
        inToken = true;
      }
    }
    if (inToken) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  static CommandLine parser() {
    var ops =
        new NoirCli.Operations(
            home -> {
              throw new AssertionError("seal must not be invoked");
            },
            home -> {
              throw new AssertionError("run must not be invoked");
            },
            () -> {
              throw new AssertionError("github must not be called");
            },
            Set::of,
            home -> {
              throw new AssertionError("dispositions must not be used");
            });
    return new CommandLine(new NoirCli(new NoirHome(Path.of("unused-noir-home")), ops));
  }

  @Test
  void everyNoirCommandInTheSkillParses() throws IOException {
    var commands = noirCommands(SkillCommandTest.skillResource());

    assertThat(commands)
        .anyMatch(c -> c.startsWith("noir draft init "))
        .anyMatch(c -> c.startsWith("noir draft check "))
        .anyMatch(c -> c.startsWith("noir draft show "))
        .anyMatch(c -> c.startsWith("noir seal "))
        .anyMatch(c -> c.startsWith("noir run --once"));

    for (var command : commands) {
      var args = tokenize(PLACEHOLDER.matcher(command).replaceAll("sample"));
      assertThat(args.getFirst()).isEqualTo("noir");
      var rest = args.subList(1, args.size()).toArray(String[]::new);
      assertThatCode(() -> parser().parseArgs(rest)).as(command).doesNotThrowAnyException();
    }
  }

  @Test
  void frontmatterHasNameAndDescription() throws IOException {
    var lines = SkillCommandTest.skillResource().lines().toList();

    assertThat(lines.getFirst()).isEqualTo("---");
    int end = lines.subList(1, lines.size()).indexOf("---") + 1;
    assertThat(end).as("closing frontmatter delimiter").isPositive();
    var frontmatter = lines.subList(1, end);
    assertThat(frontmatter).contains("name: noir-spec");
    assertThat(frontmatter)
        .filteredOn(l -> l.startsWith("description: "))
        .singleElement()
        .asString()
        .hasSizeGreaterThan("description: ".length() + 40);
  }

  @Test
  void skillNeverTellsTheAgentToWriteIntoTheRepo() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill).containsIgnoringCase("never").contains("working tree");
    assertThat(skill.lines())
        .as("lines telling the agent to write a src/ path outside the draft's stubs/")
        .filteredOn(l -> WRITE_INTO_SRC.matcher(l).find())
        .allMatch(l -> l.contains("stubs/"));
  }
}
