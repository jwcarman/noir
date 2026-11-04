package org.jwcarman.noir.agent;

import java.nio.file.Path;
import java.time.Duration;
import org.jwcarman.noir.config.NoirConfig;

/**
 * One headless agent run. {@code workDir} is mounted read-write at {@code /work}, which is also the
 * agent's working directory. {@code model == null} uses the CLI default.
 */
public record AgentRequest(
    String image,
    String systemPrompt,
    String prompt,
    Path workDir,
    String model,
    int maxTurns,
    Duration wallClock,
    NoirConfig.Limits limits) {}
