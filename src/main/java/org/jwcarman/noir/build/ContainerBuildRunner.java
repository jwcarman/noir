package org.jwcarman.noir.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.noir.agent.AgentPort;
import org.jwcarman.noir.agent.AgentRequest;
import org.jwcarman.noir.agent.AgentResult;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.Mount;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;

/**
 * Runs one builder attempt in three steps, all with {@code attemptDir} mounted at {@code /work}: a
 * prep container ({@code build-prep.sh}: clone the input bundle to {@code /work/repo} on the
 * branch), the agent (through {@link AgentPort}), and a post container ({@code build-post.sh}:
 * commit leftovers, bundle the branch). Prep and post run without network or credentials, and the
 * host never runs git in the builder's repository (spec §6.4).
 */
public final class ContainerBuildRunner implements BuildRunner {
  static final Duration GIT_TIMEOUT = Duration.ofMinutes(10);
  static final long MAX_COMMITS_BYTES = 64;

  private final ContainerPort docker;
  private final AgentPort agent;
  private final NoirConfig config;

  public ContainerBuildRunner(ContainerPort docker, AgentPort agent, NoirConfig config) {
    this.docker = docker;
    this.agent = agent;
    this.config = config;
  }

  @Override
  public BuildOutcome run(
      Spec spec,
      List<StubSource> stubs,
      Path inputBundle,
      String branch,
      Optional<String> feedback,
      Path attemptDir) {
    if (spec.baseSha() == null) {
      throw new IllegalArgumentException("spec " + spec.id() + " has no baseSha");
    }
    var env = Map.of("BRANCH", branch, "BASE_SHA", spec.baseSha());
    try {
      Files.createDirectories(attemptDir);
      for (var stale : List.of("repo", "out.bundle", "commits.txt", "FEEDBACK.md")) {
        deleteRecursively(attemptDir.resolve(stale));
      }
      copyBundle(inputBundle, attemptDir.resolve("input.bundle"));
      Files.writeString(attemptDir.resolve("SPEC.md"), SpecRenderer.specMd(spec, stubs));
      if (feedback.isPresent()) {
        Files.writeString(attemptDir.resolve("FEEDBACK.md"), feedback.get());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    require("prep", docker.run(gitStep("/opt/noir/bin/build-prep.sh", attemptDir, env)));

    AgentResult result =
        agent.run(
            new AgentRequest(
                config.images().builder(),
                BuilderPrompt.system(),
                BuilderPrompt.user(spec, stubs, feedback),
                attemptDir,
                null,
                config.budgets().turns(),
                config.budgets().wallClock(),
                config.limits()));

    require("post", docker.run(gitStep("/opt/noir/bin/build-post.sh", attemptDir, env)));

    var bundle = attemptDir.resolve("out.bundle");
    if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
      throw new DockerException("builder post step wrote no out.bundle");
    }
    return new BuildOutcome(bundle, commits(attemptDir.resolve("commits.txt")) > 0, result);
  }

  private ContainerSpec gitStep(String script, Path attemptDir, Map<String, String> env) {
    return ContainerSpec.of(config.images().builder(), script)
        .withMounts(List.of(Mount.rw(attemptDir, "/work")))
        .withWorkdir("/work")
        .withEnv(env)
        .withNetwork(false)
        .withLimits(config.limits())
        .withTimeout(GIT_TIMEOUT);
  }

  private static void require(String step, ContainerResult r) {
    if (!r.ok()) {
      throw new DockerException(
          "builder "
              + step
              + " container "
              + (r.timedOut() ? "timed out" : "exited " + r.exitCode())
              + ":\n"
              + r.tail(40));
    }
  }

  /** The commit count the post step recorded; it is untrusted, so it must be a plain integer. */
  private static int commits(Path file) {
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > MAX_COMMITS_BYTES) {
        throw new DockerException("builder post step wrote no commits.txt");
      }
      var text = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (!text.matches("\\d{1,9}")) {
        throw new DockerException("builder post step wrote a malformed commits.txt: " + text);
      }
      return Integer.parseInt(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void copyBundle(Path from, Path to) throws IOException {
    if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Not a regular bundle file: " + from);
    }
    try (InputStream in = Files.newInputStream(from, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(to);
      Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
