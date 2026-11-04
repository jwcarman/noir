package org.jwcarman.noir.agent;

import java.math.BigDecimal;

/** Outcome of an agent run, as reported by the agent CLI. */
public record AgentResult(
    int exitCode,
    boolean error,
    boolean timedOut,
    BigDecimal costUsd,
    int turns,
    String model,
    String resultText,
    String imageId) {}
