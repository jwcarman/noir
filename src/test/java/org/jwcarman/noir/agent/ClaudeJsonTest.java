package org.jwcarman.noir.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.docker.ContainerResult;

class ClaudeJsonTest {
  @Test
  void parsesCliJson() {
    var json =
        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":7,"
            + "\"total_cost_usd\":0.4213,\"result\":\"Wrote 9 tests\",\"session_id\":\"s\"}";
    var r =
        ClaudeJson.parse(new ContainerResult(0, json, "", false, "sha256:x"), "claude-opus-5-5");
    assertThat(r)
        .isEqualTo(
            new AgentResult(
                0,
                false,
                false,
                new BigDecimal("0.4213"),
                7,
                "claude-opus-5-5",
                "Wrote 9 tests",
                "sha256:x"));
  }

  @Test
  void errorResultIsError() {
    var json =
        "{\"is_error\":true,\"num_turns\":100,\"total_cost_usd\":2.5,\"result\":\"max turns\"}";
    var r = ClaudeJson.parse(new ContainerResult(1, json, "", false, "id"), null);
    assertThat(r.error()).isTrue();
    assertThat(r.turns()).isEqualTo(100);
    assertThat(r.costUsd()).isEqualByComparingTo("2.5");
  }

  @Test
  void garbageStdoutIsError() {
    var r = ClaudeJson.parse(new ContainerResult(1, "boom", "trace", false, "id"), null);
    assertThat(r.error()).isTrue();
    assertThat(r.model()).isEqualTo("default");
    assertThat(r.costUsd()).isEqualByComparingTo("0");
    assertThat(r.resultText()).contains("trace");
  }

  @Test
  void timeoutIsReported() {
    var r = ClaudeJson.parse(new ContainerResult(-1, "", "", true, "id"), "m");
    assertThat(r.timedOut()).isTrue();
    assertThat(r.error()).isTrue();
  }
}
