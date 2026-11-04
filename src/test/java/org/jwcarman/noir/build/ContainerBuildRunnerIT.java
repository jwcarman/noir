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
          .contains("noir: attempt work")
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

  /** The bundle's single new commit and its patch, inspected with noir's own BundleRepo. */
  String onlyNewCommit(BuildOutcome outcome) {
    try (var repo =
        BundleRepo.open(
            new ProcessCommandRunner(),
            outcome.bundle(),
            tmp.resolve("inspect").toAbsolutePath())) {
      assertThat(repo.commitsSince(base.sha(), BRANCH)).hasSize(1);
      return repo.patchSince(base.sha(), BRANCH);
    }
  }

  static void git(Path repo, String... args) {
    GitFixtures.git(repo, args);
  }

  @Test
  void rewrittenHistoryStillCapturesTheWorkingTree() {
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          git(repo, "checkout", "-q", "--orphan", "rewritten");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
          git(repo, "add", "-A");
          git(repo, "commit", "-q", "-m", "fresh history");
          git(repo, "branch", "-M", BRANCH);
        });
    var outcome = buildWith(agent, tmp.resolve("attempt"));
    assertThat(outcome.producedCommits()).isTrue();
    assertThat(onlyNewCommit(outcome))
        .contains("noir: attempt work")
        .contains("+++ b/" + COUNTER_PATH)
        .doesNotContain("fresh history");
  }

  @Test
  void deletedGitDirectoryStillCapturesTheWorkingTree() {
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
          deleteTree(repo.resolve(".git"));
        });
    var outcome = buildWith(agent, tmp.resolve("attempt"));
    assertThat(outcome.producedCommits()).isTrue();
    assertThat(onlyNewCommit(outcome)).contains("+++ b/" + COUNTER_PATH);
  }

  @Test
  void severalAgentCommitsAndEditsBecomeOneCommit() {
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
          git(repo, "add", "-A");
          git(repo, "commit", "-q", "-m", "agent one");
          BuildFixtures.write(repo, Map.of(COUNTER_TEST_PATH, BuildFixtures.COUNTER_TEST));
          git(repo, "add", "-A");
          git(repo, "commit", "-q", "-m", "agent two");
          BuildFixtures.write(repo, Map.of("NOTES.txt", "uncommitted\n"));
          deleteTree(repo.resolve("src/main/java/com/example/api/Placeholder.java"));
        });
    var outcome = buildWith(agent, tmp.resolve("attempt"));
    assertThat(outcome.producedCommits()).isTrue();
    assertThat(onlyNewCommit(outcome))
        .contains("noir: attempt work")
        .contains("+++ b/" + COUNTER_PATH)
        .contains("+++ b/" + COUNTER_TEST_PATH)
        .contains("+++ b/NOTES.txt")
        .contains("--- a/src/main/java/com/example/api/Placeholder.java")
        .doesNotContain("agent one")
        .doesNotContain("agent two");
  }

  @Test
  void nestedRepositoryFilterNeverRunsAndNestedGitIsDropped() {
    var attempt = tmp.resolve("attempt");
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          var sub = repo.resolve("sub");
          git(repo.getParent(), "init", "-q", sub.toString());
          git(sub, "config", "filter.z.clean", "sh -c 'touch /work/SUB_RAN; cat'");
          BuildFixtures.write(sub, Map.of(".gitattributes", "* filter=z\n", "f.txt", "one\n"));
          git(sub, "add", "-A");
          git(sub, "commit", "-q", "-m", "s");
          BuildFixtures.write(sub, Map.of("f.txt", "two\n"));
          deleteTree(work.resolve("SUB_RAN"));
          git(repo, "add", "sub");
          // gitfile variant: a submodule-style .git file pointing into .git/modules
          var mod = repo.resolve("mod");
          try {
            Files.createDirectories(repo.resolve(".git/modules"));
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          git(
              repo.getParent(),
              "init",
              "-q",
              "--separate-git-dir",
              repo.resolve(".git/modules/mod").toString(),
              mod.toString());
          git(mod, "config", "filter.z.clean", "sh -c 'touch /work/MOD_RAN; cat'");
          BuildFixtures.write(mod, Map.of(".gitattributes", "* filter=z\n", "g.txt", "one\n"));
          git(mod, "add", "-A");
          git(mod, "commit", "-q", "-m", "m");
          BuildFixtures.write(mod, Map.of("g.txt", "two\n"));
          deleteTree(work.resolve("MOD_RAN"));
          git(repo, "add", "mod");
        });
    var outcome = buildWith(agent, attempt);
    assertThat(attempt.resolve("SUB_RAN")).doesNotExist();
    assertThat(attempt.resolve("MOD_RAN")).doesNotExist();
    assertThat(outcome.producedCommits()).isTrue();
    assertThat(onlyNewCommit(outcome))
        .contains("+++ b/sub/f.txt")
        .contains("+two")
        .contains("+++ b/mod/g.txt")
        .doesNotContain("Subproject commit")
        .doesNotContainPattern("b/(sub|mod)/\\.git(/|\\n)")
        .doesNotContain(".git/modules");
  }

  static void deleteTree(Path p) {
    if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(p)) {
      for (var f : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(f);
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Appends builder-written git configuration that would run code during the post step. */
  static void plant(Path repo, String config, Map<String, String> files) {
    try {
      Files.writeString(
          repo.resolve(".git/config"), config, java.nio.file.StandardOpenOption.APPEND);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    BuildFixtures.write(repo, files);
  }

  BuildOutcome buildWith(ScriptedAgent agent, Path attempt) {
    return new ContainerBuildRunner(docker, agent, config)
        .run(
            BuildFixtures.spec(base.sha()),
            BuildFixtures.stubs(),
            base.bundle(),
            BRANCH,
            Optional.empty(),
            attempt);
  }

  @Test
  void builderFilterDriversNeverRunInThePostStep() throws Exception {
    var attempt = tmp.resolve("attempt");
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
          plant(
              repo,
              """
              [filter "x"]
              \tclean = "sh -c 'touch /work/FILTER_RAN; kill -9 $PPID; cat'"
              \tprocess = "sh -c 'touch /work/FILTER_RAN; kill -9 $PPID'"
              \trequired = true
              [core]
              \tattributesFile = /work/repo/attrs
              \tfsmonitor = "sh -c 'touch /work/FILTER_RAN'"
              """,
              Map.of(
                  ".git/info/attributes", "* filter=x\n",
                  ".gitattributes", "* filter=x\n",
                  "attrs", "* filter=x\n"));
        });
    var outcome = buildWith(agent, attempt);
    assertThat(attempt.resolve("FILTER_RAN")).doesNotExist();
    assertThat(attempt.resolve("post-error.txt")).doesNotExist();
    assertThat(outcome.producedCommits()).isTrue();
    try (var repo =
        BundleRepo.open(
            new ProcessCommandRunner(),
            outcome.bundle(),
            tmp.resolve("inspect").toAbsolutePath())) {
      assertThat(repo.patchSince(base.sha(), BRANCH))
          .contains("noir: attempt work")
          .contains("+++ b/" + COUNTER_PATH)
          .contains("+  public void reset() {");
    }
  }

  @Test
  void builderConfigIncludesAreIgnored() throws Exception {
    var attempt = tmp.resolve("attempt");
    var agent =
        new ScriptedAgent((n, work) -> {
          var repo = work.resolve("repo");
          BuildFixtures.write(repo, Map.of(COUNTER_PATH, BuildFixtures.reference()));
          plant(
              repo,
              """
              [include]
              \tpath = /work/repo/evil.config
              [includeIf "gitdir:/work/"]
              \tpath = /work/repo/evil.config
              """,
              Map.of(
                  "evil.config",
                  """
                  [filter "y"]
                  \tclean = "sh -c 'touch /work/INCLUDE_RAN; cat'"
                  \trequired = true
                  [user]
                  \tname = evil
                  """,
                  ".gitattributes",
                  "*.java filter=y\n"));
        });
    var outcome = buildWith(agent, attempt);
    assertThat(attempt.resolve("INCLUDE_RAN")).doesNotExist();
    assertThat(outcome.producedCommits()).isTrue();
    try (var repo =
        BundleRepo.open(
            new ProcessCommandRunner(),
            outcome.bundle(),
            tmp.resolve("inspect").toAbsolutePath())) {
      assertThat(repo.patchSince(base.sha(), BRANCH))
          .contains("Author: noir <noir@noir.invalid>")
          .doesNotContain("Author: evil");
    }
  }
}
