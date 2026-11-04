package org.jwcarman.noir.agent;

import java.util.Map;

/** Picks the Claude credential to pass into agent containers. */
public final class AgentAuth {
  static final String API_KEY = "ANTHROPIC_API_KEY";
  static final String OAUTH_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN";

  private AgentAuth() {}

  public static Map<String, String> fromEnvironment(Map<String, String> env) {
    for (var name : new String[] {API_KEY, OAUTH_TOKEN}) {
      var value = env.get(name);
      if (value != null && !value.isBlank()) {
        return Map.of(name, value);
      }
    }
    throw new IllegalStateException("Set " + API_KEY + " or " + OAUTH_TOKEN);
  }
}
