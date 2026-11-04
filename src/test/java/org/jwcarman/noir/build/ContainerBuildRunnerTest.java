package org.jwcarman.noir.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.FakeContainerPort;
import org.jwcarman.noir.spec.Specs;

class ContainerBuildRunnerTest {
  @TempDir Path tmp;
  Path input;
  final Map<String, String> postWrites = new HashMap<>();
  final Map<String, ContainerResult> results = new HashMap<>();
  final ScriptedAgent agent = new ScriptedAgent((n, work) -> {});
  FakeContainerPort port;

  @BeforeEach
  void setUp() throws IOException {
    input = Files.writeString(tmp.resolve("base.bundle"), "# v2 git bundle\n");
    postWrites.put("out.bundle", "# v2 git bundle\n");
    postWrites.put("commits.txt", "2\n");
    port =
        new FakeContainerPort(spec -> {
          var step = spec.command().get(0).endsWith("build-prep.sh") ? "prep" : "post";
          if (step.equals("post")) {
            var work = spec.mounts().get(0).host();
            postWrites.forEach((k, v) -> {
              try {
                Files.writeString(work.resolve(k), v);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
          }
          return results.getOrDefault(step, new ContainerResult(0, "", "", false, "sha"));
        });
  }

  BuildOutcome run() {
    return new ContainerBuildRunner(port, agent, NoirConfig.defaults())
        .run(Specs.valid(), List.of(), input, "noir/1-x", Optional.empty(), tmp.resolve("attempt"));
  }

  @Test
  void countsCommitsAndPassesBranchAndBase() throws IOException {
    var outcome = run();
    assertThat(outcome.producedCommits()).isTrue();
    assertThat(tmp.resolve("attempt/input.bundle")).hasSameTextualContentAs(input);
    assertThat(port.specs())
        .allSatisfy(s -> {
          assertThat(s.network()).isFalse();
          assertThat(s.env())
              .containsOnly(
                  Map.entry("BRANCH", "noir/1-x"), Map.entry("BASE_SHA", Specs.valid().baseSha()));
        });
    assertThat(agent.requests()).hasSize(1);
  }

  @Test
  void everyContainerAndTheAgentCarryTheItemLabel() {
    run();
    var item = Map.of(ContainerSpec.ITEM_LABEL, Specs.ID);
    assertThat(port.specs()).hasSize(2).allSatisfy(s -> assertThat(s.labels()).isEqualTo(item));
    assertThat(agent.requests().getFirst().labels()).isEqualTo(item);
  }

  @Test
  void zeroCommitsIsNoOutput() {
    postWrites.put("commits.txt", "0\n");
    assertThat(run().producedCommits()).isFalse();
  }

  @Test
  void prepFailureIsAnInfrastructureErrorAndSkipsTheAgent() {
    results.put("prep", new ContainerResult(128, "", "fatal", false, "sha"));
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("prep container exited 128");
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void postTimeoutIsABuilderFault() {
    postWrites.clear();
    results.put("post", new ContainerResult(137, "git add hung\n", "", true, "sha"));
    assertThat(run().producedCommits()).isFalse();
  }

  @Test
  void killedPostContainerIsABuilderFault() {
    postWrites.clear();
    results.put("post", new ContainerResult(137, "", "Killed\n", false, "sha"));
    var outcome = run();
    assertThat(outcome.producedCommits()).isFalse();
    assertThat(tmp.resolve("attempt/post-error.txt")).content().contains("exited 137");
  }

  @Test
  void dockerLevelPostFailuresStayInfrastructureErrors() {
    for (int exit : new int[] {125, 126, 127}) {
      results.put("post", new ContainerResult(exit, "", "docker: error", false, "sha"));
      assertThatThrownBy(this::run)
          .as("exit " + exit)
          .isInstanceOf(DockerException.class)
          .hasMessageContaining("post container exited " + exit);
    }
  }

  @Test
  void malformedCommitCountIsAnInfrastructureError() {
    postWrites.put("commits.txt", "lots\n");
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("commits.txt");
  }

  @Test
  void missingBundleIsAnInfrastructureError() {
    postWrites.remove("out.bundle");
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("out.bundle");
  }

  @Test
  void postErrorMeansNoOutputNotAnInfrastructureError() {
    postWrites.remove("out.bundle");
    postWrites.remove("commits.txt");
    postWrites.put("post-error.txt", "base commit is not an ancestor of noir/1-x\n");
    var outcome = run();
    assertThat(outcome.producedCommits()).isFalse();
  }

  @Test
  void stalePlantedSymlinksAreNeverFollowed() throws IOException {
    var attempt = Files.createDirectories(tmp.resolve("attempt"));
    var outside = Files.writeString(tmp.resolve("outside.txt"), "original");
    for (var name : List.of("SPEC.md", "FEEDBACK.md", "input.bundle", "post-error.txt")) {
      Files.createSymbolicLink(attempt.resolve(name), outside);
    }
    new ContainerBuildRunner(port, agent, NoirConfig.defaults())
        .run(Specs.valid(), List.of(), input, "noir/1-x", Optional.of("fb"), attempt);
    assertThat(outside).hasContent("original");
    for (var name : List.of("SPEC.md", "FEEDBACK.md", "input.bundle")) {
      assertThat(Files.isSymbolicLink(attempt.resolve(name))).as(name).isFalse();
      assertThat(attempt.resolve(name)).isRegularFile();
    }
    assertThat(attempt.resolve("FEEDBACK.md")).hasContent("fb");
  }

  @Test
  void postAlwaysStartsFromNoirsOwnInputBundle() throws IOException {
    var tampering =
        new ScriptedAgent((n, work) -> {
          try {
            Files.delete(work.resolve("input.bundle"));
            Files.writeString(work.resolve("input.bundle"), "tampered");
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    var seen = new java.util.ArrayList<String>();
    var recording =
        new FakeContainerPort(spec -> {
          var work = spec.mounts().get(0).host();
          try {
            seen.add(Files.readString(work.resolve("input.bundle")));
            if (spec.command().get(0).endsWith("build-post.sh")) {
              Files.writeString(work.resolve("out.bundle"), "# v2 git bundle\n");
              Files.writeString(work.resolve("commits.txt"), "1\n");
            }
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return new ContainerResult(0, "", "", false, "sha");
        });
    new ContainerBuildRunner(recording, tampering, NoirConfig.defaults())
        .run(Specs.valid(), List.of(), input, "noir/1-x", Optional.empty(), tmp.resolve("a"));
    assertThat(seen).containsExactly("# v2 git bundle\n", "# v2 git bundle\n");
  }
}
