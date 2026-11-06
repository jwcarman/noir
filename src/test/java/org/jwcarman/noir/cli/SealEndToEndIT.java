package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.factory.Retry;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.git.LocalOriginGit;
import org.jwcarman.noir.git.RepoMirror;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.NoirLabels;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.seal.ApprovalSealer;
import org.jwcarman.noir.seal.ContainerSealCheckRunner;
import org.jwcarman.noir.seal.Examiner;
import org.jwcarman.noir.seal.SealOutcome;
import org.jwcarman.noir.seal.SuiteSealer;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

@Tag("docker")
class SealEndToEndIT {
  @TempDir Path tmp;

  @Test
  void sealsAnApprovedIssueEndToEnd() throws Exception {
    var config = NoirConfig.defaults();
    var runner = new ProcessCommandRunner();
    var docker = new DockerCli(runner);
    Images.ensureBuilder(docker, config.images().builder());
    var home = new NoirHome(tmp.resolve("home"));
    var store = new Store(home);
    var github = new FakeGitHub().withNoirLabels("jwcarman/demo");
    var origin = GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo");

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
    var stubPath = "src/main/java/com/example/api/Counter.java";
    var stubs =
        List.of(new StubSource(stubPath, Files.readString(fixture("stubs").resolve(stubPath))));
    int issue =
        github.createIssue(
            "jwcarman/demo", spec.title(), SpecRenderer.issueBody(spec, stubs), List.of());
    github.createdAt(issue, Instant.parse("2026-11-01T00:00:00Z"));
    github.addLabel(issue, NoirLabels.READY, "alice", Instant.parse("2026-11-02T00:00:00Z"));
    var item = WorkItem.approved(spec.id(), "jwcarman/demo", issue);
    store.writeWorkItem(item);

    var git = new LocalOriginGit(new GitCli(runner), origin);
    var examiner =
        new Examiner(
            new ScriptedAgent((n, work) -> {
              copyTree(fixture("holdout-good"), work.resolve("out/holdout"));
              copyTree(fixture("reference"), work.resolve("out/reference"));
            }),
            config);
    var clock = Clock.systemUTC();
    var sealer =
        new ApprovalSealer(
            store,
            github,
            new RepoMirror(git, home.repos()),
            git,
            new SuiteSealer(
                store,
                examiner,
                new ContainerSealCheckRunner(docker, config),
                git,
                config,
                clock,
                Retry.forConfig(config, d -> {})),
            config,
            clock,
            Set.of());

    var outcome = sealer.seal(item);

    assertThat(outcome).isInstanceOf(SealOutcome.Passed.class);
    assertThat(github.comments(issue)).singleElement().asString().contains("noir commitment");
    assertThat(store.readEvents(spec.id()))
        .extracting(Event::type)
        .contains("approved", "commitment_posted", "sealed");
    assertThat(store.itemDir(spec.id()).resolve("commitment.json")).exists();
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
