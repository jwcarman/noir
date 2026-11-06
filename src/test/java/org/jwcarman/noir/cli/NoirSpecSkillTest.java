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

    assertThat(commands).anyMatch(c -> c.startsWith("noir spec template"));
    assertThat(commands).anyMatch(c -> c.startsWith("noir spec check"));

    for (var command : commands) {
      var withoutRedirect = command.replaceAll("\\s+>\\s*\\S+$", "");
      var args = tokenize(PLACEHOLDER.matcher(withoutRedirect).replaceAll("sample"));
      assertThat(args.getFirst()).isEqualTo("noir");
      var rest = args.subList(1, args.size()).toArray(String[]::new);
      assertThatCode(() -> parser().parseArgs(rest)).as(command).doesNotThrowAnyException();
    }
  }

  @Test
  void skillNeverAddsReadyLabel() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill).doesNotContain("--add-label noir:ready").doesNotContain("--label noir:ready");
  }

  @Test
  void skillMentionsNoCredential() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill.lines())
        .filteredOn(l -> l.contains("ANTHROPIC_API_KEY") || l.contains("CLAUDE_CODE_OAUTH_TOKEN"))
        .allMatch(l -> l.toLowerCase().contains("never needs"));
  }

  @Test
  void skillNeverReadsNoirHome() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill.lines())
        .filteredOn(l -> l.contains("~/.noir"))
        .allMatch(l -> l.toLowerCase().contains("never"));
    assertThat(skill).doesNotContain("noir draft").doesNotContain("noir seal");
  }

  @Test
  void skillUsesLiteralTempDirNotShellVariable() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill)
        .doesNotContain("$tmp")
        .contains("<tmpdir>/body.md")
        .contains("rm -rf <tmpdir>");
  }

  @Test
  void skillRefusesRevisionInEveryNonDraftState() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill)
        .contains("noir:sealing", "noir:building", "noir:pr-open", "noir:failed", "noir:blocked")
        .contains("original.md")
        .contains("--search noir:draft");
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

  @Test
  void skillOnlyOffersNewStubFilesAndChecksThemBeforePublishing() throws IOException {
    var skill = SkillCommandTest.skillResource();

    // New-file stubs must not exist; modify targets must exist (Plan 6).
    assertThat(skill)
        .contains("new-file stubs must not exist; modify targets must exist")
        .doesNotContainIgnoringCase("complete current content")
        .doesNotContainIgnoringCase("replaces that whole file")
        .doesNotContainIgnoringCase("stub for an existing file")
        .doesNotContainIgnoringCase("extend an existing class, the stub")
        .doesNotContainIgnoringCase("prefer new files")
        .doesNotContainIgnoringCase("stubs are new files only")
        .contains("git fetch origin <default-branch>")
        .contains("git cat-file -e origin/<default-branch>:<path>");
    assertThat(skill.indexOf("git cat-file -e"))
        .as("checked before the issue is created")
        .isLessThan(skill.indexOf("gh issue create"));
  }

  @Test
  void skillDescribesModifySpecs() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill)
        .contains("(modify)")
        .contains("## Tests to update")
        .contains("git show origin/<default-branch>:<path>")
        .contains("noir spec check <tmpdir>/body.md --title \"<title>\" --repo .")
        .contains("src/test/java");
    assertThat(skill).containsIgnoringCase("grep");
  }

  @Test
  void skillNeverGuessesTestsToUpdate() throws IOException {
    var skill = SkillCommandTest.skillResource();
    var from = skill.indexOf("Changing existing code");
    assertThat(from).isNotNegative();
    var section = skill.substring(from);

    assertThat(section).contains("ask").containsIgnoringCase("never guess");
  }

  @Test
  void skillExplainsChangedMemberSemantics() throws IOException {
    var skill = SkillCommandTest.skillResource();

    assertThat(skill)
        .contains("keeps its exact current signature")
        .contains("different parameter list adds an overload")
        .contains("cannot be removed or renamed")
        .contains("current body stays in the stub")
        .contains("only methods and")
        .contains("add the imports")
        .contains("breaks existing tests that this spec does not allow to change")
        .doesNotContain("conflicts_with_existing_tests");
  }
}
