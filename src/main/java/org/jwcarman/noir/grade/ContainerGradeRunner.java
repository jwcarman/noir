package org.jwcarman.noir.grade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.Mount;

/**
 * Runs {@code grade.sh} in the grader image: no network, no Maven, no credentials. Only the
 * builder's compiled classes, its dependency jars, and the sealed holdout tests are mounted.
 */
public final class ContainerGradeRunner implements GradeRunner {
  private final ContainerPort docker;
  private final NoirConfig config;

  public ContainerGradeRunner(ContainerPort docker, NoirConfig config) {
    this.docker = docker;
    this.config = config;
  }

  @Override
  public GradeArtifacts run(
      Path classesDir, Path depsDir, Path holdoutDir, String holdoutPackage, Path outDir) {
    deleteRecursively(outDir);
    try {
      Files.createDirectories(outDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var spec =
        ContainerSpec.of(config.images().grader(), "/opt/noir/bin/grade.sh")
            .withMounts(
                List.of(
                    Mount.ro(classesDir, "/work/classes"),
                    Mount.ro(depsDir, "/work/deps"),
                    Mount.ro(holdoutDir, "/work/holdout"),
                    Mount.rw(outDir, "/work/out")))
            .withEnv(
                Map.of(
                    "HOLDOUT_PKG", holdoutPackage,
                    "TEST_TIMEOUT", config.timeouts().perTest().toSeconds() + "s",
                    "SUITE_TIMEOUT", config.timeouts().suite().toSeconds() + "s"))
            .withNetwork(false)
            .withLimits(config.limits())
            .withTimeout(
                Duration.ofMinutes(5)
                    .plus(config.timeouts().suite().multipliedBy(3))
                    .plus(Duration.ofMinutes(2)));
    var r = docker.run(spec);
    var log = new StringBuilder("== grade (exit ").append(r.exitCode());
    if (r.timedOut()) {
      log.append(", timed out");
    }
    log.append(")\n").append(r.stdout()).append(r.stderr()).append('\n');
    try {
      Files.writeString(outDir.resolve("container.log"), log);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var artifacts = new GradeArtifacts(outDir);
    if (!r.ok()) {
      throw new DockerException(
          "grader container "
              + (r.timedOut() ? "timed out" : "exited " + r.exitCode())
              + ":\n"
              + r.tail(40));
    }
    requireSteps(artifacts);
    return artifacts;
  }

  /**
   * Every step the script should have finished must have recorded an exit. The forbidden scan must
   * also have exited 0 and written its list; a non-empty list is the judge's Failed verdict.
   */
  private static void requireSteps(GradeArtifacts a) {
    require(a, "forbidden");
    int scan = a.exit("forbidden").orElseThrow();
    if (scan != 0) {
      throw new DockerException("grader forbidden scan exited " + scan);
    }
    if (!a.present("forbidden.txt")) {
      throw new DockerException("grader forbidden scan wrote no forbidden.txt");
    }
    if (!a.empty("forbidden.txt")) {
      return;
    }
    require(a, "holdout-compile");
    if (a.exit("holdout-compile").orElseThrow() != 0) {
      return;
    }
    require(a, "run-1");
    if (a.exit("run-1").orElseThrow() != 0) {
      require(a, "run-2");
      require(a, "run-3");
    }
  }

  private static void require(GradeArtifacts a, String step) {
    if (a.exit(step).isEmpty()) {
      throw new DockerException("grader did not complete step " + step);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
