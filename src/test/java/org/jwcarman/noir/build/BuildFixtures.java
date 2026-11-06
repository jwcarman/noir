package org.jwcarman.noir.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.Specs;

/** Shared inputs for the builder and measurement container tests. */
public final class BuildFixtures {
  public static final String BRANCH = "noir/1-counter";

  /** Quotes, an apostrophe and a $ prove the post step passes the message to git verbatim. */
  public static final String MESSAGE = "Add a \"Counter\" that isn't $HOME-aware (#1)";

  public static final String COUNTER_PATH = "src/main/java/com/example/api/Counter.java";
  public static final String COUNTER_TEST_PATH = "src/test/java/com/example/api/CounterTest.java";

  /** The base POM plus JUnit Jupiter in test scope, as a builder would add it. */
  public static final String POM_WITH_JUNIT =
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
        %s
      </project>
      """;

  public static final String COUNTER_TEST =
      """
      package com.example.api;

      import static org.junit.jupiter.api.Assertions.assertEquals;

      import org.junit.jupiter.api.Test;

      class CounterTest {
        @Test
        void startsAtZero() {
          assertEquals(0, new Counter().get());
        }

        @Test
        void incrementAddsOne() {
          var c = new Counter();
          c.increment();
          c.increment();
          assertEquals(2, c.get());
        }

        @Test
        void resetGoesBackToZero() {
          var c = new Counter();
          c.increment();
          c.reset();
          assertEquals(0, c.get());
        }
      }
      """;

  public record Base(Path bundle, String sha) {}

  private BuildFixtures() {}

  public static String pom(String build) {
    return POM_WITH_JUNIT.formatted(build);
  }

  public static String pom() {
    return pom("");
  }

  /** A bundle of GitFixtures' one-commit repository, as noir creates it when sealing. */
  public static Base baseBundle(Path tmp) {
    var clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo"),
            tmp.resolve("clone"));
    var git = new GitCli(new ProcessCommandRunner());
    var sha = git.revParse(clone, "origin/main");
    var bundle = tmp.resolve("base.bundle");
    git.bundle(clone, sha, bundle);
    return new Base(bundle, sha);
  }

  public static Path fixture(String name) {
    try {
      return Path.of(BuildFixtures.class.getResource("/fixtures/seal/" + name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String reference() {
    return read(fixture("reference").resolve(COUNTER_PATH));
  }

  public static List<StubSource> stubs() {
    return List.of(new StubSource(COUNTER_PATH, read(fixture("stubs").resolve(COUNTER_PATH))));
  }

  public static Spec spec(String baseSha) {
    return Specs.valid().withBaseSha(baseSha).withApiStubs(List.of(COUNTER_PATH));
  }

  /** Writes repo-relative files under {@code repo}. */
  public static void write(Path repo, Map<String, String> files) {
    try {
      for (var e : files.entrySet()) {
        var p = repo.resolve(e.getKey());
        Files.createDirectories(p.getParent());
        Files.writeString(p, e.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Records every container spec while delegating to another port. */
  public static final class Recording implements ContainerPort {
    private final ContainerPort delegate;
    public final List<ContainerSpec> specs = new ArrayList<>();

    public Recording(ContainerPort delegate) {
      this.delegate = delegate;
    }

    @Override
    public ContainerResult run(ContainerSpec spec) {
      specs.add(spec);
      return delegate.run(spec);
    }

    @Override
    public Optional<String> imageId(String image) {
      return delegate.imageId(image);
    }

    @Override
    public void build(Path contextDir, String tag) {
      delegate.build(contextDir, tag);
    }

    @Override
    public int removeLabelled(String label) {
      return delegate.removeLabelled(label);
    }
  }
}
