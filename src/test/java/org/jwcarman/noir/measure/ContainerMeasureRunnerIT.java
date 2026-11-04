package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.noir.build.BuildFixtures.BRANCH;
import static org.jwcarman.noir.build.BuildFixtures.COUNTER_PATH;
import static org.jwcarman.noir.build.BuildFixtures.COUNTER_TEST_PATH;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.build.BuildFixtures;
import org.jwcarman.noir.build.ContainerBuildRunner;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.BundleRepo;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.feedback.FeedbackInput;

@Tag("docker")
class ContainerMeasureRunnerIT {
  static final DockerCli docker = new DockerCli(new ProcessCommandRunner());
  static final NoirConfig config = NoirConfig.defaults();
  static BuildFixtures.Base base;

  static final String COUNTER_WITHOUT_RESET =
      """
      package com.example.api;

      public class Counter {
        private int count;

        public void increment() {
          count++;
        }

        public int get() {
          return count;
        }
      }
      """;

  /** Tests increment and get only, so reset() is never covered. */
  static final String PARTIAL_TEST =
      """
      package com.example.api;

      import static org.junit.jupiter.api.Assertions.assertEquals;

      import org.junit.jupiter.api.Test;

      class CounterTest {
        @Test
        void incrementAddsOne() {
          var c = new Counter();
          assertEquals(0, c.get());
          c.increment();
          c.increment();
          assertEquals(2, c.get());
        }
      }
      """;

  /** Tries to overwrite judged files from inside the builder's own test run. */
  static final String HOSTILE_TEST =
"""
      package com.example.api;

      import java.nio.file.Files;
      import java.nio.file.Path;
import java.time.Duration;
      import org.junit.jupiter.api.Test;

      class HostileTest {
        static final String[] TARGETS = {
          "/work/out/changed.txt", "/work/out/generated.txt", "/work/out/forbidden.txt",
          "/work/out/holdout-sources.txt", "/work/out/javap-built.txt", "/work/out/build.exit",
          "/work/out/pit.exit", "/work/out/jacoco.xml", "/work/out/mutations.xml",
          "/work/out/stubs.txt", "/work/out/export/classes/x.class"
        };

        @Test
        void plant() {
          for (var t : TARGETS) {
            try {
              Files.createDirectories(Path.of(t).getParent());
              Files.writeString(Path.of(t), "forged\\n");
            } catch (Exception e) {
              // expected: judged files are out of reach
            }
          }
          try {
            new ProcessBuilder("sh", "-c",
                    "(for i in $(seq 1 600); do for d in /work/out /work /work/*; do"
                        + " echo forged > $d/generated.txt 2>/dev/null; done; sleep 0.1; done) &")
                .start();
          } catch (Exception e) {
            // ignore
          }
        }
      }
""";

  static final String FAKE_JACOCO_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <report name="fake"><package name="com/example/api"><class name="com/example/api/Counter"\
       sourcefilename="Counter.java"><counter type="LINE" missed="0" covered="99"/></class>\
      <sourcefile name="Counter.java"><counter type="LINE" missed="0" covered="99"/></sourcefile>\
      </package><counter type="LINE" missed="0" covered="99"/></report>
      """;

  /** A builder POM that switches its own JaCoCo off, excludes Counter, and overrides argLine. */
  static final String HOSTILE_BUILD =
      """
      <build>
        <plugins>
          <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.16</version>
            <configuration>
              <skip>true</skip>
              <excludes><exclude>com/example/api/Counter*</exclude></excludes>
            </configuration>
          </plugin>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
              <argLine>-Xmx256m</argLine>
            </configuration>
          </plugin>
        </plugins>
      </build>
      """;

  @BeforeAll
  static void setUp(@TempDir Path tmp) {
    Images.ensureBuilder(docker, config.images().builder());
    base = BuildFixtures.baseBundle(tmp);
  }

  @TempDir Path tmp;

  /** Produces an attempt bundle the way a real attempt does: through the builder containers. */
  Path attempt(Map<String, String> files, Consumer<Path> extra) {
    var agent =
        new ScriptedAgent((n, work) -> {
          BuildFixtures.write(work.resolve("repo"), files);
          extra.accept(work.resolve("repo"));
        });
    return new ContainerBuildRunner(docker, agent, config)
        .run(
            BuildFixtures.spec(base.sha()),
            BuildFixtures.stubs(),
            base.bundle(),
            BRANCH,
            Optional.empty(),
            tmp.resolve("attempt"))
        .bundle();
  }

  Path attempt(Map<String, String> files) {
    return attempt(files, repo -> {});
  }

  record Measured(
      MeasureArtifacts artifacts, MeasureOutcome outcome, BuildFixtures.Recording rec) {}

  Measured measure(Path bundle) {
    return measure(bundle, ContainerMeasureRunner.Timeouts.DEFAULT);
  }

  Measured measure(Path bundle, ContainerMeasureRunner.Timeouts timeouts) {
    var rec = new BuildFixtures.Recording(docker);
    var artifacts =
        new ContainerMeasureRunner(rec, config, timeouts)
            .run(
                BuildFixtures.spec(base.sha()),
                bundle,
                BRANCH,
                BuildFixtures.fixture("stubs"),
                tmp.resolve("measure"));
    var outcome = MeasureJudge.judge(artifacts, config.qualityBar(), artifacts.changedClasses());
    return new Measured(artifacts, outcome, rec);
  }

  static Map<String, String> correct() {
    var files = new HashMap<String, String>();
    files.put("pom.xml", BuildFixtures.pom());
    files.put(COUNTER_PATH, BuildFixtures.reference());
    files.put(COUNTER_TEST_PATH, BuildFixtures.COUNTER_TEST);
    return files;
  }

  @Test
  void correctCounterPasses() {
    var m = measure(attempt(correct()));
    assertThat(m.outcome())
        .as(m.artifacts().logTail("build", 80))
        .isInstanceOf(MeasureOutcome.Passed.class);
    assertThat(m.outcome().m().changedClasses()).containsExactly("com.example.api.Counter");
    assertThat(m.outcome().m().lineCoverage()).isEqualTo(1.0);
    assertThat(m.outcome().m().mutationScore()).isEqualTo(1.0);
    assertThat(m.artifacts().read("stubs.txt")).isEqualTo("com.example.api.Counter");
    assertThat(m.artifacts().exportClasses().resolve("com/example/api/Counter.class"))
        .isRegularFile();
    assertThat(m.artifacts().exportDeps()).isDirectory();
    assertThat(m.artifacts().report("mutations.xml")).isPresent();
    assertThat(m.artifacts().report("jacoco.xml")).isPresent();
    // Scratch is gone, and no container carried credentials.
    assertThat(tmp.resolve("measure/repo")).doesNotExist();
    assertThat(m.rec().specs).isNotEmpty();
    assertThat(m.rec().specs)
        .allSatisfy(s ->
            assertThat(s.env().keySet())
                .noneMatch(
                    k -> k.contains("ANTHROPIC") || k.contains("CLAUDE") || k.contains("GH")));
    // Only the phase that downloads dependencies has network access.
    assertThat(m.rec().specs.stream().filter(s -> s.network()).count()).isEqualTo(1);
  }

  @Test
  void missingMethodIsAContractViolation() {
    var files = correct();
    files.put(COUNTER_PATH, COUNTER_WITHOUT_RESET);
    files.put(COUNTER_TEST_PATH, PARTIAL_TEST);
    var m = measure(attempt(files));
    assertThat(m.outcome()).isInstanceOf(MeasureOutcome.Shortfall.class);
    var input = ((MeasureOutcome.Shortfall) m.outcome()).input();
    assertThat(input).isInstanceOf(FeedbackInput.ContractViolation.class);
    assertThat(((FeedbackInput.ContractViolation) input).mismatches())
        .contains("missing: public void reset();");
  }

  @Test
  void builderJacocoConfigAndFakeReportsAreIgnored() {
    var files = correct();
    files.put("pom.xml", BuildFixtures.pom(HOSTILE_BUILD));
    files.put(COUNTER_TEST_PATH, PARTIAL_TEST);
    files.put("jacoco.xml", FAKE_JACOCO_XML);
    files.put("site/jacoco/jacoco.xml", FAKE_JACOCO_XML);
    files.put("jacoco.exec", "not a real exec file\n");
    files.put("coverage/jacoco.exec", "not a real exec file\n");
    var bundle = attempt(files);
    try (var repo =
        BundleRepo.open(
            new ProcessCommandRunner(), bundle, tmp.resolve("inspect").toAbsolutePath())) {
      assertThat(repo.patchSince(base.sha(), BRANCH))
          .contains("+++ b/jacoco.xml")
          .contains("+++ b/jacoco.exec")
          .contains("+++ b/site/jacoco/jacoco.xml")
          .contains("+++ b/coverage/jacoco.exec");
    }
    var m = measure(bundle);
    assertThat(m.artifacts().read("build.log")).contains("Tests run: 1, Failures: 0");
    assertThat(m.outcome())
        .as(m.artifacts().logTail("build", 80))
        .isInstanceOf(MeasureOutcome.Shortfall.class);
    var input = ((MeasureOutcome.Shortfall) m.outcome()).input();
    assertThat(input).isInstanceOf(FeedbackInput.QualityShortfall.class);
    assertThat(m.outcome().m().changedClasses()).containsExactly("com.example.api.Counter");
    // Real coverage: increment/get/constructor covered, reset() not.
    assertThat(m.outcome().m().lineCoverage()).isGreaterThan(0.0).isLessThan(0.8);
  }

  @Test
  void builderTestsCannotWriteJudgedFiles() throws Exception {
    var files = correct();
    files.put("src/test/java/com/example/api/HostileTest.java", HOSTILE_TEST);
    var m = measure(attempt(files));
    assertThat(m.artifacts().read("build.log")).contains("Running com.example.api.HostileTest");
    assertThat(m.outcome())
        .as(m.artifacts().logTail("build", 80))
        .isInstanceOf(MeasureOutcome.Passed.class);
    assertThat(m.outcome().m().changedClasses()).containsExactly("com.example.api.Counter");
    for (var f :
        List.of(
            "changed.txt",
            "generated.txt",
            "forbidden.txt",
            "holdout-sources.txt",
            "javap-built.txt",
            "stubs.txt",
            "build.exit",
            "pit.exit")) {
      assertThat(m.artifacts().read(f)).as(f).doesNotContain("forged");
    }
    assertThat(Files.readString(m.artifacts().path("jacoco.xml"))).doesNotContain("forged");
    assertThat(m.artifacts().exportClasses().resolve("x.class")).doesNotExist();
  }

  /** Hangs forever; with {@code underPit} only when PIT's classes are on the classpath. */
  static String hangingTest(boolean underPit) {
    return """
    package com.example.api;

    import org.junit.jupiter.api.Test;

    class HangTest {
      @Test
      void hang() throws Exception {
        if (%s) {
          Thread.sleep(Long.MAX_VALUE);
        }
      }

      static boolean underPit() {
        try {
          Class.forName("org.pitest.testapi.TestUnit");
          return true;
        } catch (ClassNotFoundException e) {
          return false;
        }
      }
    }
    """
        .formatted(underPit ? "underPit()" : "true");
  }

  static final ContainerMeasureRunner.Timeouts SHORT =
      new ContainerMeasureRunner.Timeouts(
          Duration.ofSeconds(90), Duration.ofSeconds(45), Duration.ofMinutes(10));

  @Test
  void hangingBuilderTestIsABuildFailure() {
    var files = correct();
    files.put("src/test/java/com/example/api/HangTest.java", hangingTest(false));
    var m = measure(attempt(files), SHORT);
    // The script's step timeout fired (124), long before the container's own limit.
    assertThat(m.artifacts().exit("build")).contains(124);
    assertThat(m.outcome()).isInstanceOf(MeasureOutcome.Shortfall.class);
    assertThat(((MeasureOutcome.Shortfall) m.outcome()).input())
        .isInstanceOf(FeedbackInput.BuildFailed.class);
  }

  @Test
  void builderTestHangingUnderPitIsAMutationTestingFailure() {
    var files = correct();
    files.put("src/test/java/com/example/api/HangTest.java", hangingTest(true));
    var m = measure(attempt(files), SHORT);
    assertThat(m.artifacts().exit("build")).contains(0);
    assertThat(m.artifacts().exit("pit")).contains(124);
    var input = ((MeasureOutcome.Shortfall) m.outcome()).input();
    assertThat(input).isInstanceOf(FeedbackInput.BuildFailed.class);
    assertThat(((FeedbackInput.BuildFailed) input).mavenOutput())
        .startsWith("Mutation testing failed");
  }
}
