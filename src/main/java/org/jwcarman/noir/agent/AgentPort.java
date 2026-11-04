package org.jwcarman.noir.agent;

/** Runs a coding agent headlessly. Implementations are swappable (Claude Code today). */
public interface AgentPort {
  AgentResult run(AgentRequest request);
}
