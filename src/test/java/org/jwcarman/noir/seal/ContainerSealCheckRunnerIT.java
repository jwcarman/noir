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
}
