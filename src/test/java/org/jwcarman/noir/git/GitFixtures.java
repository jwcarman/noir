package org.jwcarman.noir.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.ProcessCommandRunner;

/** Builds throwaway git repositories for tests. */
public final class GitFixtures {
  static final String POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>1.0.0</version>
        <properties>
          <maven.compiler.release>21</maven.compiler.release>
          <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>
        <dependencies>
          <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>6.1.3</version>
            <scope>test</scope>
          </dependency>
        </dependencies>
      </project>
      """;

  private GitFixtures() {}

  /** A bare repository at {@code root/<owner>/<repo>.git} with one commit on {@code main}. */
  public static Path originWithCommit(Path root, String ownerRepo) {
    try {
      var work = Files.createTempDirectory("noir-fixture-work");
      git(work, "init", "-q", "-b", "main");
      Files.writeString(work.resolve("pom.xml"), POM);
      var placeholder = work.resolve("src/main/java/com/example/api/Placeholder.java");
      Files.createDirectories(placeholder.getParent());
      Files.writeString(
          placeholder, "package com.example.api;\n\npublic final class Placeholder {}\n");
      git(work, "add", ".");
      git(work, "commit", "-q", "-m", "initial");
      var origin = root.resolve(ownerRepo + ".git");
      Files.createDirectories(origin.getParent());
      git(root, "clone", "-q", "--bare", work.toString(), origin.toString());
      return origin;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A bare repository at {@code root/<owner>/<repo>.git} with {@code files} committed on main. */
  public static Path originWithFiles(
      Path root, String ownerRepo, java.util.Map<String, String> files) {
    try {
      var work = Files.createTempDirectory("noir-fixture-work");
      git(work, "init", "-q", "-b", "main");
      for (var e : files.entrySet()) {
        var file = work.resolve(e.getKey());
        Files.createDirectories(file.getParent());
        Files.writeString(file, e.getValue());
      }
      git(work, "add", ".");
      git(work, "commit", "-q", "-m", "initial");
      var origin = root.resolve(ownerRepo + ".git");
      Files.createDirectories(origin.getParent());
      git(root, "clone", "-q", "--bare", work.toString(), origin.toString());
      return origin;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Path cloneOf(Path origin, Path dest) {
    git(dest.getParent(), "clone", "-q", origin.toString(), dest.toString());
    return dest;
  }

  public static String git(Path dir, String... args) {
    var argv = new java.util.ArrayList<String>();
    argv.addAll(
        java.util.List.of(
            "git",
            "-c",
            "user.name=noir-test",
            "-c",
            "user.email=noir@example.com",
            "-c",
            "commit.gpgsign=false"));
    argv.addAll(java.util.List.of(args));
    var r = new ProcessCommandRunner().run(Command.of(argv).inDir(dir));
    if (!r.ok()) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + r.tail(20));
    }
    return r.stdout();
  }
}
