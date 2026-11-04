package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.FakeContainerPort;
import org.jwcarman.noir.docker.Mount;
import org.jwcarman.noir.spec.Specs;

class ContainerMeasureRunnerTest {
  @TempDir Path tmp;

  static final ContainerResult OK = new ContainerResult(0, "", "", false, "sha");

  /** What each phase writes, keyed by phase, then by path relative to its rw mount. */
  final Map<String, Map<String, String>> writes = new HashMap<>();

  final Map<String, ContainerResult> results = new HashMap<>();

  FakeContainerPort port;

  ContainerMeasureRunnerTest() {
    writes.put(
        "build",
        new HashMap<>(
            Map.of(
                "steps/build.exit", "0\n",
                "steps/build.log", "BUILD SUCCESS\n",
                "steps/deps.exit", "0\n",
                "steps/deps.log", "")));
    var analyze = new HashMap<String, String>();
    for (var step :
        List.of(
            "clone",
            "export",
            "holdout-sources",
            "changed",
            "forbidden",
            "generated",
            "api",
            "coverage")) {
      analyze.put(step + ".exit", "0\n");
      analyze.put(step + ".log", "");
    }
    for (var f : List.of("holdout-sources.txt", "forbidden.txt", "generated.txt")) {
      analyze.put(f, "");
    }
    analyze.put("changed.txt", "src/main/java/com/example/api/Foo.java\n");
    analyze.put("target-classes.txt", "com.example.api.Foo\n");
    analyze.put("javap-stub.txt", "x");
    analyze.put("javap-built.txt", "x");
    analyze.put("jacoco.xml", "<report/>");
    analyze.put("export/classes/.keep", "");
    analyze.put("export/deps/.keep", "");
    writes.put("analyze", analyze);
    writes.put(
        "pit",
        new HashMap<>(
            Map.of("pit.exit", "0\n", "pit.log", "", "report/mutations.xml", "<mutations/>")));
    port =
        new FakeContainerPort(spec -> {
          var phase = spec.command().get(1);
          var rw = spec.mounts().stream().filter(m -> !m.readOnly()).findFirst().orElseThrow();
          writes.getOrDefault(phase, Map.of()).forEach((k, v) -> write(rw.host().resolve(k), v));
          return results.getOrDefault(phase, OK);
        });
  }

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  MeasureArtifacts run() {
    return new ContainerMeasureRunner(port, NoirConfig.defaults())
        .run(
            Specs.valid().withApiStubs(List.of("src/main/java/com/example/api/Foo.java")),
            tmp.resolve("attempt.bundle"),
            "noir/1-x",
            tmp.resolve("stubs"),
            tmp.resolve("measure"));
  }

  ContainerSpec phase(String name) {
    return port.specs().stream()
        .filter(s -> s.command().get(1).equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void completeMeasurement() {
    var a = run();
    assertThat(a.read("stubs.txt")).isEqualTo("com.example.api.Foo");
    assertThat(a.exit("build")).contains(0);
    assertThat(a.exit("pit")).contains(0);
    assertThat(a.report("mutations.xml")).isPresent();
    assertThat(tmp.resolve("measure.scratch")).doesNotExist();
    assertThat(port.specs())
        .extracting(s -> s.command().get(1))
        .containsExactly("build", "analyze", "pit");
    assertThat(phase("build").network()).isTrue();
    assertThat(phase("analyze").network()).isFalse();
    assertThat(phase("pit").network()).isFalse();
    assertThat(phase("analyze").env())
        .containsEntry("STUB_CLASSES", "com.example.api.Foo")
        .containsEntry("BASE_SHA", Specs.valid().baseSha())
        .containsEntry("BRANCH", "noir/1-x");
    assertThat(phase("pit").env()).containsEntry("TARGET_CLASSES", "com.example.api.Foo");
    assertThat(port.specs())
        .allSatisfy(s ->
            assertThat(s.env().keySet())
                .noneMatch(k -> k.contains("ANTHROPIC") || k.contains("CLAUDE")));
  }

  @Test
  void noPhaseThatRunsBuilderCodeSeesTheOutputDirectory() {
    run();
    var out = tmp.resolve("measure");
    for (var name : List.of("build", "pit")) {
      assertThat(phase(name).mounts())
          .extracting(Mount::host)
          .noneMatch(h -> h.startsWith(out) || out.startsWith(h));
      assertThat(phase(name).mounts())
          .filteredOn(m -> !m.readOnly())
          .extracting(Mount::container)
          .doesNotContain("/work/out");
    }
    assertThat(phase("analyze").mounts())
        .filteredOn(m -> !m.readOnly())
        .extracting(Mount::container)
        .containsExactly("/work/out");
  }

  @Test
  void failedContainerIsAnInfrastructureError() {
    results.put("analyze", new ContainerResult(2, "", "boom", false, "sha"));
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("analyze container exited 2");
    assertThat(tmp.resolve("measure.scratch")).doesNotExist();
  }

  @Test
  void timedOutAnalyzeContainerIsAnInfrastructureError() {
    results.put("analyze", new ContainerResult(137, "", "", true, "sha"));
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("analyze container timed out");
  }

  @Test
  void missingStepIsAnInfrastructureError() {
    writes.get("analyze").remove("forbidden.exit");
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("step forbidden");
  }

  @Test
  void failedInfrastructureStepIsAnInfrastructureError() {
    writes.get("analyze").put("changed.exit", "128\n");
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("changed exited 128");
  }

  @Test
  void missingJudgedFileIsAnInfrastructureError() {
    writes.get("analyze").remove("generated.txt");
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("generated.txt");
  }

  @Test
  void depsFailureAfterASuccessfulBuildIsABuildFailure() {
    writes.get("build").put("steps/deps.exit", "1\n");
    writes.get("build").put("steps/deps.log", "Could not resolve dependencies\n");
    var a = run();
    assertThat(a.exit("build")).contains(1);
    assertThat(a.read("build.log")).contains("Could not resolve dependencies");
    assertThat(port.specs()).extracting(s -> s.command().get(1)).doesNotContain("pit");
  }

  @Test
  void killedBuildContainerIsABuildFailureNotAnInfrastructureError() {
    writes.put("build", new HashMap<>());
    results.put("build", new ContainerResult(137, "[INFO] Running HangTest\n", "", true, "sha"));
    var a = run();
    assertThat(a.exit("build")).contains(1);
    assertThat(a.exit("deps")).contains(1);
    assertThat(a.read("build.log")).contains("Running HangTest").contains("timed out");
    assertThat(a.exit("pit")).contains(1);
  }

  @Test
  void crashedBuildContainerKeepsValidRecords() {
    writes.get("build").remove("steps/deps.exit");
    results.put("build", new ContainerResult(1, "", "killed", false, "sha"));
    var a = run();
    assertThat(a.exit("build")).contains(1);
    assertThat(a.read("build.log")).contains("BUILD SUCCESS");
  }

  @Test
  void killedPitContainerIsAMutationTestingFailure() {
    writes.put("pit", new HashMap<>());
    results.put("pit", new ContainerResult(137, "PIT >> scanning\n", "", true, "sha"));
    var a = run();
    assertThat(a.exit("pit")).contains(1);
    assertThat(a.read("pit.log")).contains("PIT >> scanning").contains("timed out");
  }

  @Test
  void builderStepsGetTimeoutsBelowTheirContainers() {
    var t =
        new ContainerMeasureRunner.Timeouts(
            Duration.ofSeconds(30), Duration.ofSeconds(40), Duration.ofMinutes(10));
    new ContainerMeasureRunner(port, NoirConfig.defaults(), t)
        .run(
            Specs.valid().withApiStubs(List.of("src/main/java/com/example/api/Foo.java")),
            tmp.resolve("attempt.bundle"),
            "noir/1-x",
            tmp.resolve("stubs"),
            tmp.resolve("measure"));
    assertThat(phase("build").env()).containsEntry("STEP_TIMEOUT", "30");
    assertThat(phase("build").timeout()).isGreaterThan(Duration.ofSeconds(60));
    assertThat(phase("pit").env()).containsEntry("STEP_TIMEOUT", "40");
    assertThat(phase("pit").timeout()).isGreaterThan(Duration.ofSeconds(40));
  }

  @Test
  void failedCleanupDoesNotMaskTheOriginalError() throws IOException {
    results.put("analyze", new ContainerResult(2, "", "boom", false, "sha"));
    var locked = tmp.resolve("measure.scratch/build/locked");
    writes.get("build").put("locked/file", "x");
    port =
        new FakeContainerPort(spec -> {
          var phase = spec.command().get(1);
          var rw = spec.mounts().stream().filter(m -> !m.readOnly()).findFirst().orElseThrow();
          writes.getOrDefault(phase, Map.of()).forEach((k, v) -> write(rw.host().resolve(k), v));
          if (phase.equals("build")) {
            locked.toFile().setWritable(false);
          }
          return results.getOrDefault(phase, OK);
        });
    try {
      assertThatThrownBy(this::run)
          .isInstanceOf(DockerException.class)
          .hasMessageContaining("analyze container exited 2")
          .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());
    } finally {
      locked.toFile().setWritable(true);
    }
  }

  @Test
  void coverageStepThatWroteNoReportIsAnInfrastructureError() {
    writes.get("analyze").remove("jacoco.xml");
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("jacoco.xml");
  }

  @Test
  void tamperedBuildRecordCountsAsAFailedBuild() {
    writes.get("build").put("steps/build.exit", "0; echo forged\n");
    var a = run();
    assertThat(a.exit("build")).contains(1);
    assertThat(a.read("build.log")).contains("left no valid exit record");
    assertThat(port.specs()).extracting(s -> s.command().get(1)).doesNotContain("pit");
    assertThat(a.exit("pit")).contains(1);
  }

  @Test
  void symlinkedBuildRecordIsNotFollowed() throws IOException {
    writes.get("build").remove("steps/build.exit");
    var secret = Files.writeString(tmp.resolve("secret.exit"), "0\n");
    port =
        new FakeContainerPort(spec -> {
          var phase = spec.command().get(1);
          var rw = spec.mounts().stream().filter(m -> !m.readOnly()).findFirst().orElseThrow();
          writes.getOrDefault(phase, Map.of()).forEach((k, v) -> write(rw.host().resolve(k), v));
          if (phase.equals("build")) {
            try {
              Files.createSymbolicLink(rw.host().resolve("steps/build.exit"), secret);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
          return OK;
        });
    assertThat(run().exit("build")).contains(1);
  }

  @Test
  void pitThatWroteNoReportCountsAsFailedMutationTesting() {
    writes.get("pit").remove("report/mutations.xml");
    var a = run();
    assertThat(a.exit("pit")).contains(1);
    assertThat(a.read("pit.log")).contains("no usable mutations.xml");
  }

  @Test
  void noChangedClassesSkipsPit() {
    writes.get("analyze").put("target-classes.txt", "");
    writes.get("analyze").put("changed.txt", "");
    var a = run();
    assertThat(port.specs()).extracting(s -> s.command().get(1)).doesNotContain("pit");
    assertThat(a.exit("pit")).contains(0);
  }

  @Test
  void stubClassesComeFromStubPaths() {
    var spec =
        Specs.valid()
            .withApiStubs(
                List.of(
                    "src/main/java/com/example/api/Counter.java",
                    "src/main/java/Top.java",
                    "README.md"));
    assertThat(ContainerMeasureRunner.stubClasses(spec))
        .containsExactly("com.example.api.Counter", "Top");
  }
}
