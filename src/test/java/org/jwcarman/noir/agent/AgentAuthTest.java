package org.jwcarman.noir.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentAuthTest {
  @Test
  void prefersApiKey() {
    assertThat(
            AgentAuth.fromEnvironment(
                Map.of("ANTHROPIC_API_KEY", "k", "CLAUDE_CODE_OAUTH_TOKEN", "t", "PATH", "/bin")))
        .containsExactly(Map.entry("ANTHROPIC_API_KEY", "k"));
  }

  @Test
  void fallsBackToOauthToken() {
    assertThat(AgentAuth.fromEnvironment(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "t")))
        .containsExactly(Map.entry("CLAUDE_CODE_OAUTH_TOKEN", "t"));
  }

  @Test
  void blankValuesDoNotCount() {
    assertThat(
            AgentAuth.fromEnvironment(
                Map.of("ANTHROPIC_API_KEY", " ", "CLAUDE_CODE_OAUTH_TOKEN", "t")))
        .containsExactly(Map.entry("CLAUDE_CODE_OAUTH_TOKEN", "t"));
  }

  @Test
  void neitherFails() {
    assertThatThrownBy(() -> AgentAuth.fromEnvironment(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN");
  }
}
