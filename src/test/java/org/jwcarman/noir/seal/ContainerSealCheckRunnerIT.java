package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.build.BuildFixtures;
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

  /** A test helper, not a test class, that another existing test uses. */
  static final String HELPER = "src/test/java/com/example/api/Counters.java";

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
            assertEquals(0, Counters.fresh().get());
          }
        }
        """;
    var helper =
        """
        package com.example.api;

        final class Counters {
          static Counter fresh() {
            return new Counter();
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
                basics,
                HELPER,
                helper));
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("legacy-clone"));
    var head = git.revParse(clone, "origin/main");
    var b = tmp.resolve("legacy.bundle");
    git.bundle(clone, head, b);
    return b;
  }

  List<String> judgeModify(Path bundle, Path out, List<String> testsToUpdate) throws Exception {
    return judgeModify(bundle, out, testsToUpdate, fixture("reference"));
  }

  List<String> judgeModify(Path bundle, Path out, List<String> testsToUpdate, Path reference)
      throws Exception {
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
            reference);
    var artifacts = new ContainerSealCheckRunner(docker, config).run(inputs, out);
    return SealJudge.judge(artifacts, config.sealDiscrimination());
  }

  @Test
  void referenceCannotReplaceOrAddRepositoryTests(@TempDir Path tmp) throws Exception {
    var legacyBundle = modifyBaseBundle(tmp);
    var reference = tmp.resolve("reference");
    var counter = reference.resolve(COUNTER);
    Files.createDirectories(counter.getParent());
    Files.copy(fixture("reference").resolve(COUNTER), counter);
    var forged = reference.resolve(LEGACY_TEST);
    Files.createDirectories(forged.getParent());
    Files.writeString(
        forged,
        """
        package com.example.api;

        import org.junit.jupiter.api.Test;

        class LegacyCounterTest {
          @Test
          void incrementAddsTwo() {}
        }
        """);
    Files.writeString(
        forged.resolveSibling("ForgedTest.java"),
        """
        package com.example.api;

        import org.junit.jupiter.api.Test;

        class ForgedTest {
          @Test
          void fails() {
            throw new AssertionError("forged");
          }
        }
        """);

    var out = tmp.resolve("forged");
    assertThat(judgeModify(legacyBundle, out, List.of(), reference))
        .containsExactly(
            "existing test fails with the reference: com.example.api.LegacyCounterTest#incrementAddsTwo");
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

  /**
   * A listed file may be a helper other tests use, so listed files are still compiled; only the
   * test classes in them are left out of the run (noir-bookmarks #5).
   */
  @Test
  void listedHelperStillCompilesForTheOtherTests(@TempDir Path tmp) throws Exception {
    var legacyBundle = modifyBaseBundle(tmp);
    var listed = tmp.resolve("listed-helper");
    assertThat(judgeModify(legacyBundle, listed, List.of(LEGACY_TEST, HELPER))).isEmpty();
    assertThat(new SealArtifacts(listed).exit("existing-tests")).contains(0);
    assertThat(listed.resolve("existing-tests/TEST-com.example.api.CounterBasicsTest.xml"))
        .exists();
    assertThat(listed.resolve("existing-tests/TEST-com.example.api.LegacyCounterTest.xml"))
        .doesNotExist();
  }

  /** A one-commit repository whose test scope adds AssertJ to JUnit. */
  static Path assertjBundle(Path tmp) {
    var pom =
        BuildFixtures.POM_WITH_JUNIT
            .formatted(
                """
                <!-- assertj below -->
                """)
            .replace(
                "</dependencies>",
                """
                  <dependency>
                    <groupId>org.assertj</groupId>
                    <artifactId>assertj-core</artifactId>
                    <version>3.27.3</version>
                    <scope>test</scope>
                  </dependency>
                </dependencies>
                """);
    var origin =
        GitFixtures.originWithFiles(
            tmp.resolve("remotes"),
            "jwcarman/assertj",
            java.util.Map.of(
                "pom.xml",
                pom,
                "src/main/java/com/example/api/Placeholder.java",
                "package com.example.api;\n\npublic final class Placeholder {}\n"));
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("assertj-clone"));
    var b = tmp.resolve("assertj.bundle");
    git.bundle(clone, git.revParse(clone, "origin/main"), b);
    return b;
  }

  /** The repository's test-scope libraries (AssertJ here) are on the hidden tests' classpath. */
  @Test
  void holdoutSeesTestScopeDependencies(@TempDir Path tmp) throws Exception {
    var b = assertjBundle(tmp);
    var head = git.revParse(tmp.resolve("assertj-clone"), "origin/main");
    var inputs =
        SealInputs.of(
            Specs.valid().withBaseSha(head),
            b,
            fixture("stubs"),
            fixture("holdout-assertj"),
            fixture("reference"));
    var artifacts = new ContainerSealCheckRunner(docker, config).run(inputs, out);
    assertThat(SealJudge.judge(artifacts, config.sealDiscrimination()))
        .as(artifacts.logTail("holdout-compile", 40))
        .isEmpty();
    var jars = artifacts.read("deps.txt").lines().toList();
    assertThat(jars).anyMatch(n -> n.startsWith("assertj-core-") && n.endsWith(".jar"));
    assertThat(jars)
        .noneMatch(n ->
            n.startsWith("junit-")
                || n.startsWith("opentest4j-")
                || n.startsWith("apiguardian-api-"));
  }

  @Test
  void newFileSpecsDoNotRunExistingTests() throws Exception {
    assertThat(judge("holdout-good")).isEmpty();
    assertThat(out.resolve("test-deps.exit")).doesNotExist();
    assertThat(out.resolve("existing-tests.exit")).doesNotExist();
  }

  @Test
  void inventoryListsLibrariesAndPackages(@TempDir Path tmp) throws Exception {
    var b = assertjBundle(tmp);
    var head = git.revParse(tmp.resolve("assertj-clone"), "origin/main");

    var files = new ContainerSealCheckRunner(docker, config).inventory(b, head, out);

    assertThat(files.libraries())
        .as(new SealArtifacts(out).logTail("inventory", 40))
        .contains("org.assertj:assertj-core:3.27.3")
        .noneMatch(
            l -> l.contains("junit") || l.contains("opentest4j") || l.contains("apiguardian"));
    assertThat(files.packages())
        .contains("org.assertj.core.api")
        .noneMatch(p ->
            p.startsWith("org.junit")
                || p.startsWith("org.opentest4j")
                || p.startsWith("org.apiguardian"));
    assertThat(out.resolve(RepoInventoryFiles.LIBRARIES)).exists();
    assertThat(out.resolve(RepoInventoryFiles.PACKAGES)).exists();
    assertThat(out.resolve("repo")).doesNotExist();
  }

  /** JUnit's own artifacts are never listed, though what they depend on (jspecify) is. */
  @Test
  void inventoryLeavesOutJUnitsOwnArtifacts() throws Exception {
    var files = new ContainerSealCheckRunner(docker, config).inventory(bundle, sha, out);
    assertThat(files.libraries())
        .as(new SealArtifacts(out).logTail("inventory", 40))
        .contains("org.jspecify:jspecify:1.0.0")
        .noneMatch(l ->
            l.startsWith("org.junit")
                || l.startsWith("org.opentest4j")
                || l.startsWith("org.apiguardian"));
    assertThat(files.packages())
        .contains("org.jspecify.annotations")
        .noneMatch(p ->
            p.startsWith("org.junit")
                || p.startsWith("org.opentest4j")
                || p.startsWith("org.apiguardian"));
  }

  @Test
  void inventoryOfARepositoryWithoutAPomIsEmpty(@TempDir Path tmp) throws Exception {
    var origin =
        GitFixtures.originWithFiles(
            tmp.resolve("remotes"), "jwcarman/nopom", java.util.Map.of("README.md", "hi\n"));
    var clone = GitFixtures.cloneOf(origin, tmp.resolve("nopom-clone"));
    var head = git.revParse(clone, "origin/main");
    var b = tmp.resolve("nopom.bundle");
    git.bundle(clone, head, b);

    var files = new ContainerSealCheckRunner(docker, config).inventory(b, head, out);
    assertThat(files).isEqualTo(new RepoInventoryFiles(List.of(), List.of()));
  }
}
