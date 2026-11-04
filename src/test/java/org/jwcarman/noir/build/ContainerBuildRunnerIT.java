package org.jwcarman.noir.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.noir.build.BuildFixtures.BRANCH;
import static org.jwcarman.noir.build.BuildFixtures.COUNTER_PATH;
import static org.jwcarman.noir.build.BuildFixtures.COUNTER_TEST_PATH;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.deliver.BundleRepo;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitFixtures;

@Tag("docker")
class ContainerBuildRunnerIT {
  static final DockerCli docker = new DockerCli(new ProcessCommandRunner());
  static final NoirConfig config = NoirConfig.defaults();
  static BuildFixtures.Base base;

  @BeforeAll
  static void setUp(@TempDir Path tmp) {
    Images.ensureBuilder(docker, config.images().builder());
    base = BuildFixtures.baseBundle(tmp);
  }

  @TempDir Path tmp;

  @Test
  void uncommittedWorkIsCommittedAndBundled() throws Exception {
    var recording = new BuildFixtures.Recording(docker);
    var agent =
        new ScriptedAgent((n, work) ->
            BuildFixtures.write(
                work.resolve("repo"),
                Map.of(
                    "pom.xml",
                    BuildFixtures.pom(),
                    COUNTER_PATH,
                    BuildFixtures.reference(),
                    COUNTER_TEST_PATH,
                    BuildFixtures.COUNTER_TEST)));
    var attempt = tmp.resolve("attempts/1");
    var outcome =
        new ContainerBuildRunner(recording, agent, config)
            .run(
                BuildFixtures.spec(base.sha()),
                BuildFixtures.stubs(),
                base.bundle(),
                BRANCH,
                Optional.of("fix the counter"),
                attempt);

    assertThat(outcome.producedCommits()).isTrue();
    assertThat(outcome.bundle()).isEqualTo(attempt.resolve("out.bundle"));
    assertThat(outcome.agent().resultText()).isEqualTo("done");
    try (var repo =
        BundleRepo.open(
            new ProcessCommandRunner(),
            outcome.bundle(),
            tmp.resolve("inspect").toAbsolutePath())) {
      assertThat(repo.commitsSince(base.sha(), BRANCH)).hasSize(1);
      assertThat(repo.patchSince(base.sha(), BRANCH))
          .contains("noir: uncommitted work at agent exit")
          .contains("+++ b/" + COUNTER_PATH)
          .contains("+++ b/" + COUNTER_TEST_PATH)
          .contains("Author: noir <noir@noir.invalid>");
    }

    var request = agent.requests().get(0);
    assertThat(request.workDir()).isEqualTo(attempt);
    assertThat(request.systemPrompt()).isEqualTo(BuilderPrompt.system());
    assertThat(request.prompt()).contains("# FEEDBACK.md").contains("fix the counter");
    assertThat(request.maxTurns()).isEqualTo(config.budgets().turns());
    assertThat(request.wallClock()).isEqualTo(config.budgets().wallClock());
    assertThat(attempt.resolve("SPEC.md")).exists();
    assertThat(Files.readString(attempt.resolve("FEEDBACK.md"))).isEqualTo("fix the counter");

    assertThat(recording.specs).hasSize(2);
    assertThat(recording.specs)
        .allSatisfy(s -> {
          assertThat(s.network()).isFalse();
          assertThat(s.env().keySet())
              .noneMatch(k -> k.contains("ANTHROPIC") || k.contains("CLAUDE") || k.contains("GH"));
          assertThat(s.image()).isEqualTo(config.images().builder());
        });
    assertThat(recording.specs.get(0).command()).contains("/opt/noir/bin/build-prep.sh");
    assertThat(recording.specs.get(1).command()).contains("/opt/noir/bin/build-post.sh");
  }

  @Test
  void agentCommitsOnTheBranchAreKept() throws Exception {
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
        });
    var outcome =
        new ContainerBuildRunner(docker, agent, config)
            .run(
                BuildFixtures.spec(base.sha()),
                BuildFixtures.stubs(),
                base.bundle(),
                BRANCH,
                Optional.empty(),
                tmp.resolve("a1"));
    assertThat(outcome.producedCommits()).isTrue();
    assertThat(tmp.resolve("a1/FEEDBACK.md")).doesNotExist();

    // A second attempt starts from the first attempt's bundle, on the same branch.
    var second =
        new ContainerBuildRunner(
                docker,
                new ScriptedAgent((n, work) ->
                    BuildFixtures.write(work.resolve("repo"), Map.of("NOTES.txt", "second\n"))),
                config)
            .run(
                BuildFixtures.spec(base.sha()),
                BuildFixtures.stubs(),
                outcome.bundle(),
                BRANCH,
                Optional.empty(),
                tmp.resolve("a2"));
    try (var repo =
        BundleRepo.open(
            new ProcessCommandRunner(), second.bundle(), tmp.resolve("inspect").toAbsolutePath())) {
      assertThat(repo.commitsSince(base.sha(), BRANCH)).hasSize(2);
    }
  }

  @Test
  void noOutputMeansNoCommits() {
    var outcome =
        new ContainerBuildRunner(docker, new ScriptedAgent((n, work) -> {}), config)
            .run(
                BuildFixtures.spec(base.sha()),
                BuildFixtures.stubs(),
                base.bundle(),
                BRANCH,
                Optional.empty(),
                tmp.resolve("attempt"));
    assertThat(outcome.producedCommits()).isFalse();
    assertThat(outcome.bundle()).isRegularFile();
  }

  @Test
  void historyRewrittenPastTheBaseIsNoOutput() {
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          GitFixtures.git(repo, "checkout", "-q", "--orphan", "rewritten");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
          GitFixtures.git(repo, "add", "-A");
          GitFixtures.git(repo, "commit", "-q", "-m", "fresh history");
          GitFixtures.git(repo, "branch", "-M", BRANCH);
        });
    var attempt = tmp.resolve("attempt");
    var outcome =
        new ContainerBuildRunner(docker, agent, config)
            .run(
                BuildFixtures.spec(base.sha()),
                BuildFixtures.stubs(),
                base.bundle(),
                BRANCH,
                Optional.empty(),
                attempt);
    assertThat(outcome.producedCommits()).isFalse();
    assertThat(attempt.resolve("post-error.txt")).content().contains("not an ancestor");
  }

  @Test
  void destroyedRepositoryIsNoOutput() {
    var agent =
        new ScriptedAgent((n, work) -> {
          try (var paths = Files.walk(work.resolve("repo/.git"))) {
            for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
              Files.delete(p);
            }
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
    var attempt = tmp.resolve("attempt");
    var outcome =
        new ContainerBuildRunner(docker, agent, config)
            .run(
                BuildFixtures.spec(base.sha()),
                BuildFixtures.stubs(),
                base.bundle(),
                BRANCH,
                Optional.empty(),
                attempt);
    assertThat(outcome.producedCommits()).isFalse();
    assertThat(attempt.resolve("post-error.txt")).exists();
  }
}
