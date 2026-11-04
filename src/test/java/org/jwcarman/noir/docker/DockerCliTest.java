package org.jwcarman.noir.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.FakeCommandRunner;

class DockerCliTest {
  final FakeCommandRunner runner = new FakeCommandRunner();
  final DockerCli docker = new DockerCli(runner, () -> "noir-test1");

  static CommandResult ok(String out) {
    return new CommandResult(0, out, "", false, false);
  }

  @Test
  void runArgvIsHardenedAndKeepsEnvOutOfArgvAndClientEnv(@TempDir Path dir) throws Exception {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), ok("ok"));
    var envFileSeen = new String[2];
    runner.onRun(c -> {
      var i = c.argv().indexOf("--env-file");
      if (i >= 0) {
        var f = Path.of(c.argv().get(i + 1));
        try {
          envFileSeen[0] = java.nio.file.Files.readString(f);
          envFileSeen[1] =
              java.nio.file.attribute.PosixFilePermissions.toString(
                  java.nio.file.Files.getPosixFilePermissions(f));
        } catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }
    });
    var spec =
        ContainerSpec.of("noir-builder:0.1.0", "sh", "-c", "true")
            .withMounts(List.of(Mount.ro(dir, "/work/in")))
            .withEnv(Map.of("ANTHROPIC_API_KEY", "sk-secret", "HOME", "/home/noir"))
            .withNetwork(false)
            .withLimits(new NoirConfig.Limits(2.0, "4g", 512))
            .withWorkdir("/work")
            .withLabels(Map.of(ContainerSpec.ITEM_LABEL, "nr-01ABC"))
            .withStdin("PROMPT".getBytes());
    var r = docker.run(spec);
    var run = runner.commands().get(1);
    var envFile = run.argv().get(run.argv().indexOf("--env-file") + 1);
    assertThat(run.argv())
        .containsExactly(
            "docker",
            "run",
            "--rm",
            "--name",
            "noir-test1",
            "--init",
            "-i",
            "--user",
            "1000:1000",
            "--security-opt",
            "no-new-privileges",
            "--cpus",
            "2.0",
            "--memory",
            "4g",
            "--pids-limit",
            "512",
            "--network",
            "none",
            "--label",
            "noir.run=1",
            "--label",
            "noir.item=nr-01ABC",
            "-w",
            "/work",
            "-v",
            dir.toRealPath() + ":/work/in:ro",
            "--env-file",
            envFile,
            "noir-builder:0.1.0",
            "sh",
            "-c",
            "true");
    assertThat(String.join(" ", run.argv())).doesNotContain("sk-secret");
    assertThat(run.env()).as("the docker client's own environment is untouched").isEmpty();
    assertThat(envFileSeen[0]).isEqualTo("ANTHROPIC_API_KEY=sk-secret\nHOME=/home/noir\n");
    assertThat(envFileSeen[1]).isEqualTo("rw-------");
    assertThat(Path.of(envFile)).doesNotExist();
    assertThat(new String(run.stdin())).isEqualTo("PROMPT");
    assertThat(r.imageId()).isEqualTo("sha256:abc");
    assertThat(r.stdout()).isEqualTo("ok");
  }

  @Test
  void newerDaemonDownMessageIsExplained() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(
        List.of("docker", "run"),
        new CommandResult(
            1,
            "",
            "failed to connect to the docker API at unix:///var/run/docker.sock\n",
            false,
            false));
    assertThatThrownBy(() -> docker.run(ContainerSpec.of("img")))
        .isInstanceOf(DockerException.class)
        .hasMessage("Docker is not running; start Docker Desktop");
  }

  @Test
  void envValuesWithNewlinesAreRejected() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    assertThatThrownBy(() -> docker.run(ContainerSpec.of("img").withEnv(Map.of("X", "a\nb"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("X");
  }

  @Test
  void networkOnUsesBridgeAndRwMountHasNoSuffix(@TempDir Path dir) throws Exception {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), ok(""));
    docker.run(ContainerSpec.of("img").withMounts(List.of(Mount.rw(dir, "/work"))));
    var argv = runner.commands().get(1).argv();
    assertThat(argv).containsSubsequence("--network", "bridge");
    assertThat(argv).contains(dir.toRealPath() + ":/work");
  }

  @Test
  void runnerFailureStillRemovesTheContainerAndIsADockerException() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), ok(""));
    runner.whenArgvStartsWith(List.of("docker", "rm"), ok(""));
    runner.onRun(c -> {
      if (c.argv().get(1).equals("run")) {
        throw new IllegalStateException("Cannot read process output: java.lang.OutOfMemoryError");
      }
    });
    assertThatThrownBy(() -> docker.run(ContainerSpec.of("img")))
        .isInstanceOf(DockerException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(runner.commands().get(2).argv()).containsExactly("docker", "rm", "-f", "noir-test1");
  }

  @Test
  void dockerExceptionFromTheRunnerStillRemovesTheContainer() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), ok(""));
    runner.whenArgvStartsWith(List.of("docker", "rm"), ok(""));
    var boom = new DockerException("boom");
    runner.onRun(c -> {
      if (c.argv().get(1).equals("run")) {
        throw boom;
      }
    });
    assertThatThrownBy(() -> docker.run(ContainerSpec.of("img"))).isSameAs(boom);
    assertThat(runner.commands().get(2).argv()).containsExactly("docker", "rm", "-f", "noir-test1");
  }

  @Test
  void timeoutRemovesContainer() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), new CommandResult(-1, "", "", true, false));
    runner.whenArgvStartsWith(List.of("docker", "rm"), ok(""));
    var r = docker.run(ContainerSpec.of("img").withTimeout(Duration.ofSeconds(1)));
    assertThat(r.timedOut()).isTrue();
    assertThat(runner.commands().get(2).argv()).containsExactly("docker", "rm", "-f", "noir-test1");
  }

  @Test
  void timeoutWithFailedRemovalIsAnInfrastructureError() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), new CommandResult(-1, "", "", true, false));
    runner.whenArgvStartsWith(
        List.of("docker", "rm"), new CommandResult(1, "", "error: device busy\n", false, false));
    assertThatThrownBy(() -> docker.run(ContainerSpec.of("img")))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("noir-test1")
        .hasMessageContaining("device busy");
  }

  @Test
  void timeoutWhereTheContainerIsAlreadyGoneIsFine() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), new CommandResult(-1, "", "", true, false));
    runner.whenArgvStartsWith(
        List.of("docker", "rm"),
        new CommandResult(
            1, "", "Error response from daemon: No such container: x\n", false, false));
    assertThat(docker.run(ContainerSpec.of("img")).timedOut()).isTrue();
  }

  @Test
  void neverMountsDockerSocket() {
    assertThatThrownBy(() ->
            docker.run(
                ContainerSpec.of("img")
                    .withMounts(List.of(Mount.rw(Path.of("/var/run/docker.sock"), "/s")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("docker.sock");
  }

  @Test
  void daemonDownIsExplained() {
    runner.whenArgvStartsWith(
        List.of("docker", "image", "inspect"),
        new CommandResult(
            1,
            "",
            "Cannot connect to the Docker daemon at unix:///var/run/docker.sock\n",
            false,
            false));
    assertThatThrownBy(() -> docker.imageId("img"))
        .isInstanceOf(DockerException.class)
        .hasMessage("Docker is not running; start Docker Desktop");
  }

  @Test
  void missingImageIsEmpty() {
    runner.whenArgvStartsWith(
        List.of("docker", "image", "inspect"),
        new CommandResult(1, "", "Error: No such image: img\n", false, false));
    assertThat(docker.imageId("img")).isEmpty();
  }

  @Test
  void build(@TempDir Path dir) {
    runner.whenArgvStartsWith(List.of("docker", "build"), ok("sha256:def\n"));
    docker.build(dir, "noir-builder:0.1.0");
    var c = runner.commands().getFirst();
    assertThat(c.argv())
        .containsExactly("docker", "build", "-q", "-t", "noir-builder:0.1.0", dir.toString());
    assertThat(c.timeout()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void buildFailureCarriesTail(@TempDir Path dir) {
    runner.whenArgvStartsWith(
        List.of("docker", "build"), new CommandResult(1, "", "step 3 failed\n", false, false));
    assertThatThrownBy(() -> docker.build(dir, "t"))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("step 3 failed");
  }

  @Test
  void everyContainerIsLabelledAsNoirsEvenWithoutAnItem() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), ok("ok"));
    docker.run(ContainerSpec.of("img", "true"));
    var argv = runner.commands().get(1).argv();
    assertThat(argv).containsSequence("--label", "noir.run=1");
    assertThat(argv).filteredOn(a -> a.equals("--label")).hasSize(1);
  }

  @Test
  void removeLabelledRemovesEveryMatchingContainer() {
    runner.whenArgvStartsWith(List.of("docker", "ps"), ok("abc123\ndef456\n"));
    runner.whenArgvStartsWith(List.of("docker", "rm"), ok("abc123\ndef456\n"));

    int removed = docker.removeLabelled(DockerCli.RUN_LABEL);

    assertThat(removed).isEqualTo(2);
    assertThat(runner.commands())
        .extracting(c -> c.argv())
        .containsExactly(
            List.of("docker", "ps", "-aq", "--filter", "label=noir.run=1"),
            List.of("docker", "rm", "-f", "abc123", "def456"));
  }

  @Test
  void removeLabelledWithNothingLeftRunsNoRm() {
    runner.whenArgvStartsWith(List.of("docker", "ps"), ok("\n"));

    assertThat(docker.removeLabelled(DockerCli.RUN_LABEL)).isZero();
    assertThat(runner.commands()).hasSize(1);
  }

  @Test
  void removeLabelledFailureIsADockerException() {
    runner.whenArgvStartsWith(List.of("docker", "ps"), ok("abc123\n"));
    runner.whenArgvStartsWith(
        List.of("docker", "rm"), new CommandResult(1, "", "permission denied", false, false));

    assertThatThrownBy(() -> docker.removeLabelled(DockerCli.RUN_LABEL))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void removeLabelledListingFailureIsADockerException() {
    runner.whenArgvStartsWith(
        List.of("docker", "ps"),
        new CommandResult(1, "", "Cannot connect to the Docker daemon", false, false));

    assertThatThrownBy(() -> docker.removeLabelled(DockerCli.RUN_LABEL))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("Docker is not running");
  }
}
