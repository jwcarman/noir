package org.jwcarman.noir.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.FakeContainerPort;
import org.jwcarman.noir.docker.Mount;

class ClaudeContainerAgentTest {
  static final String OK =
      "{\"is_error\":false,\"num_turns\":1,\"total_cost_usd\":0.1,\"result\":\"ok\"}";

  @Test
  void containerInvocation(@TempDir Path work) {
    var docker = new FakeContainerPort(spec -> new ContainerResult(0, OK, "", false, "id"));
    var result =
        new ClaudeContainerAgent(docker, Map.of("CLAUDE_CODE_OAUTH_TOKEN", "tok"))
            .run(
                new AgentRequest(
                    "noir-builder:0.1.0",
                    "SYS",
                    "PROMPT",
                    work,
                    null,
                    100,
                    Duration.ofMinutes(30),
                    NoirConfig.defaults().limits()));
    var spec = docker.specs().getFirst();
    assertThat(spec.image()).isEqualTo("noir-builder:0.1.0");
    assertThat(spec.command())
        .containsExactly(
            "claude",
            "-p",
            "--output-format",
            "json",
            "--max-turns",
            "100",
            "--dangerously-skip-permissions",
            "--append-system-prompt",
            "SYS");
    assertThat(new String(spec.stdin(), UTF_8)).isEqualTo("PROMPT");
    assertThat(spec.network()).isTrue();
    assertThat(spec.mounts()).containsExactly(Mount.rw(work, "/work"));
    assertThat(spec.workdir()).isEqualTo("/work");
    assertThat(spec.env())
        .containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "tok")
        .containsEntry("HOME", "/home/noir");
    assertThat(spec.timeout()).isEqualTo(Duration.ofMinutes(30));
    assertThat(result.resultText()).isEqualTo("ok");
    assertThat(result.model()).isEqualTo("default");
  }

  @Test
  void modelFlagWhenRequested(@TempDir Path work) {
    var docker = new FakeContainerPort(spec -> new ContainerResult(0, OK, "", false, "id"));
    var result =
        new ClaudeContainerAgent(docker, Map.of("ANTHROPIC_API_KEY", "k"))
            .run(
                new AgentRequest(
                    "img",
                    "SYS",
                    "P",
                    work,
                    "claude-opus-5-5",
                    5,
                    Duration.ofMinutes(1),
                    NoirConfig.defaults().limits()));
    assertThat(docker.specs().getFirst().command()).endsWith("--model", "claude-opus-5-5");
    assertThat(result.model()).isEqualTo("claude-opus-5-5");
  }
}
