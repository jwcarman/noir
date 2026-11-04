package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.seal.ContainerSealCheckRunner;
import org.jwcarman.noir.seal.Examiner;
import org.jwcarman.noir.seal.Sealer;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Store;

@Tag("docker")
class SealEndToEndIT {
  @TempDir Path tmp;

  @Test
  void sealsARealDraftThroughTheCli() throws Exception {
    var config = NoirConfig.defaults();
    var runner = new ProcessCommandRunner();
    var docker = new DockerCli(runner);
    Images.ensureBuilder(docker, config.images().builder());
    var home = new NoirHome(tmp.resolve("home"));
    var store = new Store(home);
    var github = new FakeGitHub();
    var clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo"),
            tmp.resolve("clone"));

    var spec =
        Specs.valid()
            .withTitle("Counter")
            .withApiStubs(List.of("src/main/java/com/example/api/Counter.java"))
            .withRequirements(
                List.of(
                    new Requirement(
                        "R1",
                        "increment adds one; a new counter reads zero",
                        List.of(new Example("a new counter", "increment()", "get() returns 1"))),
                    new Requirement(
                        "R2",
                        "reset sets the count to zero",
                        List.of(new Example("a counter at 1", "reset()", "get() returns 0")))));
    var draft = store.draftDir(spec.id());
    SpecJson.write(spec, draft.resolve("spec.json"));
    copyTree(fixture("stubs"), draft.resolve("stubs"));

    var examiner =
        new Examiner(
            new ScriptedAgent((n, work) -> {
              copyTree(fixture("holdout-good"), work.resolve("out/holdout"));
              copyTree(fixture("reference"), work.resolve("out/reference"));
            }),
            config);
    var sealer =
        new Sealer(
            store,
            new GitCli(runner),
            github,
            examiner,
            new ContainerSealCheckRunner(docker, config),
            config,
            Clock.systemUTC());

    var out = new StringWriter();
    var err = new StringWriter();
    int code =
        NoirCli.execute(
            home,
            new PrintWriter(out),
            new PrintWriter(err),
            h -> sealer::seal,
            "seal",
            spec.id(),
            "--repo-dir",
            clone.toString());

    assertThat(code).as(out + "\n" + err).isZero();
    assertThat(out.toString()).startsWith("Sealed " + spec.id() + " as jwcarman/demo#1");
    assertThat(store.readWorkItem(spec.id()).state()).isEqualTo(State.SEALED);
    assertThat(github.issue(1).comments()).singleElement().asString().contains("noir commitment");
  }

  static Path fixture(String name) {
    try {
      return Path.of(SealEndToEndIT.class.getResource("/fixtures/seal/" + name).toURI());
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static void copyTree(Path from, Path to) {
    try (var paths = Files.walk(from)) {
      for (var p : paths.toList()) {
        var dest = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          Files.copy(p, dest);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
