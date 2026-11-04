package org.jwcarman.noir.grade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.feedback.RequirementResult;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.holdout.TagIndex;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Specs;

@Tag("docker")
class ContainerGradeRunnerIT {
  static final DockerCli docker = new DockerCli(new ProcessCommandRunner());
  static final NoirConfig config = NoirConfig.defaults();

  @BeforeAll
  static void image() {
    Images.ensureGrader(docker, config.images().grader());
  }

  @TempDir Path tmp;

  /** Records every container spec while delegating to Docker. */
  static final class Recording implements ContainerPort {
    final List<ContainerSpec> specs = new ArrayList<>();

    @Override
    public ContainerResult run(ContainerSpec spec) {
      specs.add(spec);
      return docker.run(spec);
    }

    @Override
    public Optional<String> imageId(String image) {
      return docker.imageId(image);
    }

    @Override
    public void build(Path contextDir, String tag) {
      docker.build(contextDir, tag);
    }
  }

  static Path fixture(String name) throws URISyntaxException {
    return Path.of(ContainerGradeRunnerIT.class.getResource("/fixtures/seal/" + name).toURI());
  }

  static void javac(Path classes, List<Path> sources) throws IOException {
    Files.createDirectories(classes);
    var argv = new ArrayList<>(List.of("javac", "--release", "21", "-d", classes.toString()));
    sources.forEach(s -> argv.add(s.toString()));
    var r = new ProcessCommandRunner().run(Command.of(argv));
    assertThat(r.ok()).as(r.tail(20)).isTrue();
  }

  static List<Path> javaFiles(Path root) throws IOException {
    try (Stream<Path> s = Files.walk(root)) {
      return s.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }

  record Graded(GradeOutcome outcome, Recording recording) {}

  Graded grade(Path classes) throws Exception {
    var holdout = fixture("holdout-good");
    var deps = Files.createDirectories(tmp.resolve("deps"));
    var tags =
        TagIndex.of(HoldoutAnalyzer.analyze(Specs.valid(), holdout, Set.of("com.example.api")));
    var recording = new Recording();
    var artifacts =
        new ContainerGradeRunner(recording, config)
            .run(classes, deps, holdout, ItemId.holdoutPackage(Specs.ID), tmp.resolve("out"));
    var outcome = GradeJudge.judge(artifacts, tags, List.of("R1", "R2"));
    return new Graded(outcome, recording);
  }

  @Test
  void referenceImplementationPasses() throws Exception {
    var classes = tmp.resolve("classes");
    javac(classes, javaFiles(fixture("reference")));
    var g = grade(classes);
    assertThat(g.outcome())
        .isEqualTo(
            new GradeOutcome.Passed(
                List.of(new RequirementResult("R1", 3, 3), new RequirementResult("R2", 1, 1))));
    assertThat(g.recording().specs).hasSize(1);
    assertThat(g.recording().specs.get(0).network()).isFalse();
  }

  @Test
  void wrongImplementationFails() throws Exception {
    var src = Files.createDirectories(tmp.resolve("wrong-src/com/example/api"));
    Files.writeString(
        src.resolve("Counter.java"),
        """
        package com.example.api;

        public class Counter {
          private int count;

          public void increment() {
            count += 2;
          }

          public int get() {
            return count;
          }

          public void reset() {
            count = 0;
          }
        }
        """);
    var classes = tmp.resolve("classes");
    javac(classes, javaFiles(tmp.resolve("wrong-src")));
    var g = grade(classes);
    assertThat(g.outcome()).isInstanceOf(GradeOutcome.Failed.class);
    var results = ((GradeOutcome.Failed) g.outcome()).results();
    var r1 = results.get(0);
    assertThat(r1.requirementId()).isEqualTo("R1");
    assertThat(r1.total()).isEqualTo(3);
    assertThat(r1.passed()).isLessThan(r1.total());
    assertThat(g.recording().specs.get(0).network()).isFalse();
  }
}
