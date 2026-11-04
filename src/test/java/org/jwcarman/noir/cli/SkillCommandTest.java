package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;

class SkillCommandTest {
  @TempDir Path tmp;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();

  static String skillResource() throws IOException {
    try (var in = SkillCommandTest.class.getResourceAsStream("/skills/noir-spec/SKILL.md")) {
      assertThat(in).as("SKILL.md resource").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  int run(String... args) {
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
            Set::of);
    return NoirCli.execute(
        new NoirHome(tmp.resolve("home")), new PrintWriter(out), new PrintWriter(err), ops, args);
  }

  @Test
  void installCopiesSkill() throws IOException {
    var dir = tmp.resolve("skills");

    assertThat(run("skill", "install", "--dir", dir.toString())).isZero();

    var installed = dir.resolve("noir-spec/SKILL.md");
    assertThat(installed).isRegularFile();
    assertThat(Files.readString(installed)).isEqualTo(skillResource());
    assertThat(out.toString()).contains(installed.toString());
    assertThat(err.toString()).isEmpty();
  }

  @Test
  void installOverwritesExisting() throws IOException {
    var dir = tmp.resolve("skills");
    var installed = dir.resolve("noir-spec/SKILL.md");
    Files.createDirectories(installed.getParent());
    Files.writeString(installed, "stale skill");

    assertThat(run("skill", "install", "--dir", dir.toString())).isZero();

    assertThat(Files.readString(installed)).isEqualTo(skillResource());
  }

  @Test
  void installDefaultsToClaudeSkillsUnderUserHome() throws IOException {
    var original = System.getProperty("user.home");
    var fakeHome = tmp.resolve("user");
    System.setProperty("user.home", fakeHome.toString());
    try {
      assertThat(run("skill", "install")).isZero();
    } finally {
      System.setProperty("user.home", original);
    }

    var installed = fakeHome.resolve(".claude/skills/noir-spec/SKILL.md");
    assertThat(Files.readString(installed)).isEqualTo(skillResource());
  }

  @Test
  void installFailsWithOneLineErrorWhenDirIsAFile() throws IOException {
    var file = tmp.resolve("not-a-dir");
    Files.writeString(file, "x");

    assertThat(run("skill", "install", "--dir", file.toString())).isEqualTo(1);

    assertThat(err.toString().lines()).singleElement().asString().startsWith("Cannot install");
  }
}
