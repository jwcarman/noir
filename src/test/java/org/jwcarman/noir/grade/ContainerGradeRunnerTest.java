package org.jwcarman.noir.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.FakeContainerPort;

class ContainerGradeRunnerTest {
  @TempDir Path tmp;

  static ContainerResult result(int exit, boolean timedOut) {
    return new ContainerResult(exit, "", "", timedOut, "sha");
  }

  ContainerGradeRunner runner(ContainerResult r, Map<String, String> files) {
    return new ContainerGradeRunner(
        new FakeContainerPort(spec -> {
          var out = spec.mounts().get(3).host();
          files.forEach((k, v) -> {
            try {
              Files.writeString(out.resolve(k), v);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
          return r;
        }),
        NoirConfig.defaults());
  }

  GradeArtifacts run(ContainerGradeRunner r) {
    return r.run(tmp, tmp, tmp, "p", tmp.resolve("out"));
  }

  static final Map<String, String> GOOD =
      Map.of("forbidden.exit", "0", "holdout-compile.exit", "0", "run-1.exit", "0");

  @Test
  void completeRunReturnsArtifacts() {
    assertThat(run(runner(result(0, false), GOOD)).exit("run-1")).contains(0);
  }

  @Test
  void nonZeroContainerExitIsInfrastructureFailure() {
    assertThatThrownBy(() -> run(runner(result(137, false), GOOD)))
        .isInstanceOf(DockerException.class);
  }

  @Test
  void timeoutIsInfrastructureFailure() {
    assertThatThrownBy(() -> run(runner(result(0, true), GOOD)))
        .isInstanceOf(DockerException.class);
  }

  @Test
  void missingStepExitIsInfrastructureFailure() {
    assertThatThrownBy(() ->
            run(
                runner(
                    result(0, false), Map.of("forbidden.exit", "0", "holdout-compile.exit", "0"))))
        .isInstanceOf(DockerException.class);
  }

  @Test
  void compileFailureNeedsNoRunExit() {
    var files = Map.of("forbidden.exit", "0", "holdout-compile.exit", "1");
    assertThat(run(runner(result(0, false), files)).exit("holdout-compile")).contains(1);
  }

  @Test
  void failingRun1RequiresReruns() {
    var files =
        Map.of(
            "forbidden.exit",
            "0",
            "holdout-compile.exit",
            "0",
            "run-1.exit",
            "1",
            "run-2.exit",
            "1");
    assertThatThrownBy(() -> run(runner(result(0, false), files)))
        .isInstanceOf(DockerException.class);
  }

  @Test
  void forbiddenListNeedsNoFurtherSteps() {
    var files = Map.of("forbidden.exit", "0", "forbidden.txt", "x");
    assertThat(run(runner(result(0, false), files)).read("forbidden.txt")).isEqualTo("x");
  }

  @Test
  void containerTimeoutCoversThreeSuiteRuns() {
    var port = new FakeContainerPort(spec -> result(0, false));
    var config = NoirConfig.defaults();
    try {
      new ContainerGradeRunner(port, config).run(tmp, tmp, tmp, "p", tmp.resolve("out"));
    } catch (DockerException expected) {
      // exits are missing; only the spec matters here
    }
    assertThat(port.specs().get(0).timeout())
        .isEqualTo(Duration.ofMinutes(7).plus(config.timeouts().suite().multipliedBy(3)));
  }
}
