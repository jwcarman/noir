package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.spec.Specs;

@Tag("docker")
class ContainerSealCheckRunnerIT {
  static final DockerCli docker = new DockerCli(new ProcessCommandRunner());
  static final GitCli git = new GitCli(new ProcessCommandRunner());
  static final NoirConfig config = NoirConfig.defaults();
  static Path bundle;
  static String sha;

  @BeforeAll
  static void setUp(@TempDir Path tmp) {
    Images.ensureBuilder(docker, config.images().builder());
    var clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo"),
            tmp.resolve("clone"));
    sha = git.revParse(clone, "origin/main");
    bundle = tmp.resolve("base.bundle");
    git.bundle(clone, sha, bundle);
  }

  @TempDir Path out;

  List<String> judge(String holdout) throws Exception {
    var inputs =
        SealInputs.of(
            Specs.valid().withBaseSha(sha),
            bundle,
            fixture("stubs"),
            fixture(holdout),
            fixture("reference"));
    var artifacts = new ContainerSealCheckRunner(docker, config).run(inputs, out);
    return SealJudge.judge(artifacts, config.sealDiscrimination());
  }

  static Path fixture(String name) throws Exception {
    return Path.of(ContainerSealCheckRunnerIT.class.getResource("/fixtures/seal/" + name).toURI());
  }

  @Test
  void goodSuiteSeals() throws Exception {
    assertThat(judge("holdout-good")).isEmpty();
    assertThat(Files.exists(out.resolve("pit/mutations.xml"))).isTrue();
  }

  @Test
  void vacuousSuiteRejected() throws Exception {
    assertThat(judge("holdout-vacuous"))
        .anyMatch(p -> p.startsWith("holdout test passes against the stubs (vacuous)"));
  }

  @Test
  void assertionFreeSuiteRejectedByDiscrimination() throws Exception {
    assertThat(judge("holdout-weak"))
        .anyMatch(p -> p.startsWith("holdout tests are not discriminating enough"));
  }

  @Test
  void targetClassesComeFromReference() throws Exception {
    var inputs =
        SealInputs.of(
            Specs.valid(), bundle, fixture("stubs"), fixture("holdout-good"), fixture("reference"));
    assertThat(inputs.targetClasses()).containsExactly("com.example.api.Counter");
  }

  static final String COUNTER = "src/main/java/com/example/api/Counter.java";
  static final String LEGACY_TEST = "src/test/java/com/example/api/LegacyCounterTest.java";

  /**
   * A base whose {@code Counter} counts by two, with a test pinning that (which the reference,
   * counting by one, breaks) and a test the reference keeps passing.
   */
  static Path modifyBaseBundle(Path tmp) {
    var pom =
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
              <version>5.11.4</version>
              <scope>test</scope>
            </dependency>
          </dependencies>
        </project>
        """;
    var counter =
        """
        package com.example.api;

        /** A counter that starts at zero. */
        public class Counter {
          private int count;

          /** Adds two to the count. */
          public void increment() {
            count += 2;
          }

          /** Returns the current count. */
          public int get() {
            return count;
          }

          /** Sets the count back to zero. */
          public void reset() {
            count = 0;
          }
        }
        """;
    var legacy =
        """
        package com.example.api;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import org.junit.jupiter.api.Test;

        class LegacyCounterTest {
          @Test
          void incrementAddsTwo() {
            var c = new Counter();
            c.increment();
            assertEquals(2, c.get());
          }
        }
        """;
    var basics =
        """
        package com.example.api;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import org.junit.jupiter.api.Test;

        class CounterBasicsTest {
          @Test
          void startsAtZero() {
            assertEquals(0, new Counter().get());
          }
        }
        """;
    var origin =
        GitFixtures.originWithFiles(
            tmp.resolve("remotes"),
            "jwcarman/legacy",
            java.util.Map.of(
                "pom.xml",
                pom,
                COUNTER,
                counter,
                LEGACY_TEST,
                legacy,
                "src/test/java/com/example/api/CounterBasicsTest.java",
                basics));
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("legacy-clone"));
    var head = git.revParse(clone, "origin/main");
    var b = tmp.resolve("legacy.bundle");
    git.bundle(clone, head, b);
    return b;
  }

  List<String> judgeModify(Path bundle, Path out, List<String> testsToUpdate) throws Exception {
    var spec =
        Specs.valid()
            .withApiStubs(List.of(COUNTER))
            .withModifies(List.of(COUNTER))
            .withTestsToUpdate(testsToUpdate)
            .withSchemaVersion(2);
    var clone = bundle.resolveSibling("legacy-clone");
    var inputs =
        SealInputs.of(
            spec.withBaseSha(git.revParse(clone, "origin/main")),
            bundle,
            fixture("stubs"),
            fixture("holdout-good"),
            fixture("reference"));
    var artifacts = new ContainerSealCheckRunner(docker, config).run(inputs, out);
    return SealJudge.judge(artifacts, config.sealDiscrimination());
  }

  @Test
  void existingTestsRunAgainstReferenceExcludingListed(@TempDir Path tmp) throws Exception {
    var legacyBundle = modifyBaseBundle(tmp);

    var conflicting = tmp.resolve("unlisted");
    assertThat(judgeModify(legacyBundle, conflicting, List.of()))
        .containsExactly(
            "existing test fails with the reference: com.example.api.LegacyCounterTest#incrementAddsTwo");
    assertThat(new SealArtifacts(conflicting).exit("existing-tests")).contains(1);

    var listed = tmp.resolve("listed");
    assertThat(judgeModify(legacyBundle, listed, List.of(LEGACY_TEST))).isEmpty();
    assertThat(new SealArtifacts(listed).exit("existing-tests")).contains(0);
    assertThat(listed.resolve("existing-tests/TEST-com.example.api.CounterBasicsTest.xml"))
        .exists();
    assertThat(listed.resolve("existing-tests/TEST-com.example.api.LegacyCounterTest.xml"))
        .doesNotExist();
  }

  @Test
  void newFileSpecsDoNotRunExistingTests() throws Exception {
    assertThat(judge("holdout-good")).isEmpty();
    assertThat(out.resolve("test-deps.exit")).doesNotExist();
    assertThat(out.resolve("existing-tests.exit")).doesNotExist();
  }
}
