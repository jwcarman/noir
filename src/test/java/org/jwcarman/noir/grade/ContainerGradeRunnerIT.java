package org.jwcarman.noir.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.docker.Mount;
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
  static class Recording implements ContainerPort {
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
    return grade(classes, Files.createDirectories(tmp.resolve("deps")));
  }

  Graded grade(Path classes, Path deps) throws Exception {
    var holdout = fixture("holdout-good");
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

  @Test
  void cheatingCounterCannotForgeTheResult() throws Exception {
    var src = Files.createDirectories(tmp.resolve("cheat-src/com/example/api"));
    Files.writeString(
        src.resolve("Counter.java"),
        """
        package com.example.api;

        public class Counter {
          static {
            try {
              java.nio.file.Files.writeString(
                  java.nio.file.Path.of("/work/out/run-1/TEST-junit-jupiter.xml"), "<testsuite/>");
            } catch (Throwable t) {
              // denied by the security manager
            }
            try {
              Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));
            } catch (Throwable t) {
              // denied by the security manager
            }
            try {
              Runtime.getRuntime().halt(0);
            } catch (Throwable t) {
              // denied by the security manager
            }
          }

          private int count;

          public void increment() {
            count += 2;
          }

          public int get() {
            return count;
          }

          public void reset() {
            count = 1;
          }
        }
        """);
    var classes = tmp.resolve("classes");
    javac(classes, javaFiles(tmp.resolve("cheat-src")));
    assertThat(grade(classes).outcome()).isInstanceOf(GradeOutcome.Failed.class);
  }

  @Test
  void builderAnnotationProcessorDoesNotAffectHoldoutCompilation() throws Exception {
    var src = Files.createDirectories(tmp.resolve("proc-src/com/example/evil"));
    Files.writeString(
        src.resolve("Boom.java"),
        """
        package com.example.evil;

        public class Boom extends javax.annotation.processing.AbstractProcessor {
          public Boom() {
            throw new IllegalStateException("processor must never run");
          }

          @Override
          public boolean process(
              java.util.Set<? extends javax.lang.model.element.TypeElement> a,
              javax.annotation.processing.RoundEnvironment r) {
            return false;
          }
        }
        """);
    var classes = tmp.resolve("classes");
    var sources = new ArrayList<>(javaFiles(fixture("reference")));
    sources.addAll(javaFiles(tmp.resolve("proc-src")));
    javac(classes, sources);
    var services = Files.createDirectories(classes.resolve("META-INF/services"));
    Files.writeString(
        services.resolve("javax.annotation.processing.Processor"), "com.example.evil.Boom\n");
    assertThat(grade(classes).outcome()).isInstanceOf(GradeOutcome.Passed.class);
  }

  @Test
  void holdoutLookalikeInBuilderClassesFailsWithoutRunning() throws Exception {
    var classes = tmp.resolve("classes");
    javac(classes, javaFiles(fixture("reference")));
    var shadow = Files.createDirectories(classes.resolve("noir/holdout"));
    Files.writeString(shadow.resolve("Shadow.class"), "x");
    var g = grade(classes);
    assertThat(g.outcome())
        .isEqualTo(
            new GradeOutcome.Failed(
                List.of(new RequirementResult("R1", 0, 0), new RequirementResult("R2", 0, 0))));
  }

  static final GradeOutcome ZERO =
      new GradeOutcome.Failed(
          List.of(new RequirementResult("R1", 0, 0), new RequirementResult("R2", 0, 0)));

  Path referenceClasses() throws Exception {
    var classes = tmp.resolve("classes");
    javac(classes, javaFiles(fixture("reference")));
    return classes;
  }

  @Test
  void correctCounterWithForbiddenActionsStillPassesAndIsDenied() throws Exception {
    var src = Files.createDirectories(tmp.resolve("sneaky-src/com/example/api"));
    Files.writeString(
        src.resolve("Counter.java"),
        """
        package com.example.api;

        public class Counter {
          static {
            try {
              java.nio.file.Files.writeString(
                  java.nio.file.Path.of("/work/out/run-1/TEST-junit-jupiter.xml"), "<testsuite/>");
            } catch (Throwable t) {
              t.printStackTrace();
            }
            try {
              Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));
            } catch (Throwable t) {
              t.printStackTrace();
            }
            try {
              Runtime.getRuntime().halt(0);
            } catch (Throwable t) {
              t.printStackTrace();
            }
          }

          private int count;

          public void increment() {
            count++;
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
    javac(classes, javaFiles(tmp.resolve("sneaky-src")));
    var g = grade(classes);
    assertThat(g.outcome())
        .isEqualTo(
            new GradeOutcome.Passed(
                List.of(new RequirementResult("R1", 3, 3), new RequirementResult("R2", 1, 1))));
    var artifacts = new GradeArtifacts(tmp.resolve("out"));
    assertThat(artifacts.read("run-1.log")).contains("AccessControlException");
    assertThat(artifacts.outcomes("run-1")).hasSize(4);
  }

  @Test
  void builderJunitPlatformPropertiesFailsAsForbidden() throws Exception {
    var classes = referenceClasses();
    Files.writeString(
        classes.resolve("junit-platform.properties"),
        "junit.jupiter.execution.timeout.mode=disabled\n");
    var g = grade(classes);
    assertThat(g.outcome()).isEqualTo(ZERO);
    assertThat(new GradeArtifacts(tmp.resolve("out")).read("forbidden.txt"))
        .contains("junit-platform.properties");
  }

  @Test
  void jarWithClassPathManifestFailsAsForbidden() throws Exception {
    var classes = referenceClasses();
    var deps = Files.createDirectories(tmp.resolve("deps"));
    var content = Files.createDirectories(tmp.resolve("jar-content"));
    Files.writeString(content.resolve("a.txt"), "a");
    var manifest = tmp.resolve("m.mf");
    Files.writeString(manifest, "Manifest-Version: 1.0\nClass-Path: other.jar\n");
    var r =
        new ProcessCommandRunner()
            .run(
                Command.of(
                    "jar",
                    "--create",
                    "--file",
                    deps.resolve("x.jar").toString(),
                    "--manifest",
                    manifest.toString(),
                    "-C",
                    content.toString(),
                    "."));
    assertThat(r.ok()).as(r.tail(20)).isTrue();
    assertThat(grade(classes, deps).outcome()).isEqualTo(ZERO);
    assertThat(new GradeArtifacts(tmp.resolve("out")).read("forbidden.txt")).contains("Class-Path");
  }

  @Test
  void strayNonJarInDepsFailsAsForbidden() throws Exception {
    var classes = referenceClasses();
    var deps = Files.createDirectories(tmp.resolve("deps"));
    Files.writeString(deps.resolve("notes.txt"), "x");
    assertThat(grade(classes, deps).outcome()).isEqualTo(ZERO);
    assertThat(new GradeArtifacts(tmp.resolve("out")).read("forbidden.txt")).contains("notes.txt");
  }

  /**
   * Grades the reference classes with the image's scanner replaced (test-only bind mount) by one
   * whose {@code main} body is {@code body}, then checks the tests never ran and the host refused
   * the incomplete grade.
   */
  void assertBrokenScanSkipsTests(String body) throws Exception {
    var scanner = tmp.resolve("FakeScan.java");
    Files.writeString(
        scanner,
        "public class FakeScan { public static void main(String[] args) throws Exception { "
            + body
            + " } }");
    var classes = referenceClasses();
    var deps = Files.createDirectories(tmp.resolve("deps"));
    var holdout = fixture("holdout-good");
    var port =
        new Recording() {
          @Override
          public ContainerResult run(ContainerSpec spec) {
            var mounts = new ArrayList<>(spec.mounts());
            mounts.add(Mount.ro(scanner, "/opt/noir/bin/ForbiddenScan.java"));
            return super.run(spec.withMounts(mounts));
          }
        };
    var runner = new ContainerGradeRunner(port, config);
    var out = tmp.resolve("out");
    assertThatThrownBy(
            () -> runner.run(classes, deps, holdout, ItemId.holdoutPackage(Specs.ID), out))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("forbidden");
    assertThat(out.resolve("forbidden.exit")).exists();
    assertThat(out.resolve("holdout-compile.exit")).doesNotExist();
    assertThat(out.resolve("holdout-compile.log")).doesNotExist();
    assertThat(out.resolve("run-1.exit")).doesNotExist();
    assertThat(out.resolve("run-1")).doesNotExist();
  }

  @Test
  void scannerCrashWithoutListSkipsTests() throws Exception {
    assertBrokenScanSkipsTests("System.exit(1);");
  }

  @Test
  void scannerExitingCleanWithoutListSkipsTests() throws Exception {
    assertBrokenScanSkipsTests("System.exit(0);");
  }

  @Test
  void scannerFailingAfterEmptyListSkipsTests() throws Exception {
    assertBrokenScanSkipsTests(
        "java.nio.file.Files.write(java.nio.file.Path.of(args[2]), new byte[0]);"
            + " System.exit(3);");
  }

  /** ZipFile.getEntry("x") also matches "x/", so this entry is served as the properties file. */
  @Test
  void jarWithTrailingSlashJunitPropertiesFailsAsForbidden() throws Exception {
    var classes = referenceClasses();
    var deps = Files.createDirectories(tmp.resolve("deps"));
    try (var zip =
        new java.util.zip.ZipOutputStream(Files.newOutputStream(deps.resolve("slash.jar")))) {
      zip.putNextEntry(new java.util.zip.ZipEntry("junit-platform.properties/"));
      zip.write(
          "junit.jupiter.execution.timeout.mode=disabled\n"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertThat(grade(classes, deps).outcome()).isEqualTo(ZERO);
    var artifacts = new GradeArtifacts(tmp.resolve("out"));
    assertThat(artifacts.read("forbidden.txt")).contains("junit-platform.properties/");
    assertThat(artifacts.exit("forbidden")).contains(0);
    assertThat(tmp.resolve("out/run-1")).doesNotExist();
  }
}
