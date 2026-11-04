package org.jwcarman.noir.agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.Mount;

/** Runs headless Claude Code inside a container, with the prompt on stdin. */
public final class ClaudeContainerAgent implements AgentPort {
  private final ContainerPort docker;
  private final Map<String, String> auth;

  public ClaudeContainerAgent(ContainerPort docker, Map<String, String> auth) {
    this.docker = docker;
    this.auth = Map.copyOf(auth);
  }

  @Override
  public AgentResult run(AgentRequest request) {
    var command =
        new ArrayList<>(
            List.of(
                "claude",
                "-p",
                "--output-format",
                "json",
                "--max-turns",
                String.valueOf(request.maxTurns()),
                "--dangerously-skip-permissions",
                "--append-system-prompt",
                request.systemPrompt()));
    if (request.model() != null) {
      command.addAll(List.of("--model", request.model()));
    }
    var env = new HashMap<>(auth);
    env.put("HOME", "/home/noir");
    var spec =
        ContainerSpec.of(request.image())
            .withCommand(command)
            .withMounts(List.of(Mount.rw(request.workDir(), "/work")))
            .withWorkdir("/work")
            .withEnv(env)
            .withNetwork(true)
            .withLimits(request.limits())
            .withTimeout(request.wallClock())
            .withLabels(request.labels())
            .withStdin(request.prompt().getBytes(UTF_8));
    return ClaudeJson.parse(docker.run(spec), request.model());
  }
}
