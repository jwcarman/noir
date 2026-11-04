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
    return new CommandResult(0, out, "", false);
  }

  @Test
  void runArgvIsHardenedAndHidesEnvValues(@TempDir Path dir) throws Exception {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), ok("ok"));
    var spec =
        ContainerSpec.of("noir-builder:0.1.0", "sh", "-c", "true")
            .withMounts(List.of(Mount.ro(dir, "/work/in")))
            .withEnv(Map.of("ANTHROPIC_API_KEY", "sk-secret"))
            .withNetwork(false)
            .withLimits(new NoirConfig.Limits(2.0, "4g", 512))
            .withWorkdir("/work")
            .withStdin("PROMPT".getBytes());
    var r = docker.run(spec);
    var run = runner.commands().get(1);
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
            "-w",
            "/work",
            "-v",
            dir.toRealPath() + ":/work/in:ro",
            "-e",
            "ANTHROPIC_API_KEY",
            "noir-builder:0.1.0",
            "sh",
            "-c",
            "true");
    assertThat(String.join(" ", run.argv())).doesNotContain("sk-secret");
    assertThat(run.env()).containsEntry("ANTHROPIC_API_KEY", "sk-secret");
    assertThat(new String(run.stdin())).isEqualTo("PROMPT");
    assertThat(r.imageId()).isEqualTo("sha256:abc");
    assertThat(r.stdout()).isEqualTo("ok");
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
  void timeoutRemovesContainer() {
    runner.whenArgvStartsWith(List.of("docker", "image", "inspect"), ok("sha256:abc\n"));
    runner.whenArgvStartsWith(List.of("docker", "run"), new CommandResult(-1, "", "", true));
    runner.whenArgvStartsWith(List.of("docker", "rm"), ok(""));
    var r = docker.run(ContainerSpec.of("img").withTimeout(Duration.ofSeconds(1)));
    assertThat(r.timedOut()).isTrue();
    assertThat(runner.commands().get(2).argv()).containsExactly("docker", "rm", "-f", "noir-test1");
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
            1, "", "Cannot connect to the Docker daemon at unix:///var/run/docker.sock\n", false));
    assertThatThrownBy(() -> docker.imageId("img"))
        .isInstanceOf(DockerException.class)
        .hasMessage("Docker is not running; start Docker Desktop");
  }

  @Test
  void missingImageIsEmpty() {
    runner.whenArgvStartsWith(
        List.of("docker", "image", "inspect"),
        new CommandResult(1, "", "Error: No such image: img\n", false));
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
        List.of("docker", "build"), new CommandResult(1, "", "step 3 failed\n", false));
    assertThatThrownBy(() -> docker.build(dir, "t"))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("step 3 failed");
  }
}
