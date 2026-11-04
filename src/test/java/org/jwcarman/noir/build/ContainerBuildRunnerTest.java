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
  void postTimeoutIsAnInfrastructureError() {
    results.put("post", new ContainerResult(137, "", "", true, "sha"));
    assertThatThrownBy(this::run)
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("post container timed out");
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
}
