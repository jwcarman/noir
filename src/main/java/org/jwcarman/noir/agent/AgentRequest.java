package org.jwcarman.noir.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.jwcarman.noir.config.NoirConfig;

/**
 * One headless agent run. {@code workDir} is mounted read-write at {@code /work}, which is also the
 * agent's working directory. {@code model == null} uses the CLI default. {@code labels} are Docker
 * labels for the agent's container (such as the work item it runs for).
 */
public record AgentRequest(
    String image,
    String systemPrompt,
    String prompt,
    Path workDir,
    String model,
    int maxTurns,
    Duration wallClock,
    NoirConfig.Limits limits,
    Map<String, String> labels) {

  public AgentRequest {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  /** A request whose container carries no labels beyond noir's own. */
  public AgentRequest(
      String image,
      String systemPrompt,
      String prompt,
      Path workDir,
      String model,
      int maxTurns,
      Duration wallClock,
      NoirConfig.Limits limits) {
    this(image, systemPrompt, prompt, workDir, model, maxTurns, wallClock, limits, Map.of());
  }

  public AgentRequest withLabels(Map<String, String> v) {
    return new AgentRequest(
        image, systemPrompt, prompt, workDir, model, maxTurns, wallClock, limits, v);
  }
}
