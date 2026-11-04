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
  void modelIdComesFromModelUsage() {
    var json =
        "{\"is_error\":false,\"num_turns\":2,\"total_cost_usd\":0.2,\"result\":\"ok\","
            + "\"modelUsage\":{\"claude-opus-5-5\":{\"inputTokens\":10}}}";
    var r = ClaudeJson.parse(new ContainerResult(0, json, "", false, "id"), null);
    assertThat(r.model()).isEqualTo("claude-opus-5-5");
  }

  @Test
  void forgedModelIdFallsBackToTheRequestedModel() {
    var forged =
        "{\"is_error\":false,\"num_turns\":2,\"result\":\"ok\","
            + "\"modelUsage\":{\"sk-ant-api03-SECRET value\\n### hi\":{\"inputTokens\":10}}}";
    var requested = ClaudeJson.parse(new ContainerResult(0, forged, "", false, "id"), "opus");
    assertThat(requested.model()).isEqualTo("opus");
    var none = ClaudeJson.parse(new ContainerResult(0, forged, "", false, "id"), null);
    assertThat(none.model()).isEqualTo("unknown");
    var tooLong = "{\"modelUsage\":{\"" + "a".repeat(101) + "\":{}}}";
    assertThat(ClaudeJson.parse(new ContainerResult(0, tooLong, "", false, "id"), null).model())
        .isEqualTo("unknown");
    var bracketed = "{\"modelUsage\":{\"claude-opus-5-5[1m]\":{}}}";
    assertThat(ClaudeJson.parse(new ContainerResult(0, bracketed, "", false, "id"), null).model())
        .isEqualTo("claude-opus-5-5[1m]");
  }

  @Test
  void resultLineIsFoundInTheTailAfterAFloodOfOutput() {
    var json =
        "{\"is_error\":false,\"num_turns\":3,\"result\":\"ok\","
            + "\"modelUsage\":{\"claude-opus-5-5\":{}}}";
    var r =
        new org.jwcarman.noir.exec.ProcessCommandRunner()
            .run(
                org.jwcarman.noir.exec.Command.of(
                        "sh", "-c", "yes noise | head -c 40000002; printf '%s\\n' \"$0\"", json)
                    .withOutputLimit(1024 * 1024));
    assertThat(r.truncated()).isTrue();
    var parsed = ClaudeJson.parse(new ContainerResult(0, r.stdout(), "", false, "id"), null);
    assertThat(parsed.error()).isFalse();
    assertThat(parsed.turns()).isEqualTo(3);
    assertThat(parsed.model()).isEqualTo("claude-opus-5-5");
  }

  @Test
  void invalidKeyResultCarriesMessage() {
    var json =
        "{\"is_error\":true,\"num_turns\":1,\"total_cost_usd\":0,\"api_error_status\":401,"
            + "\"result\":\"Invalid API key · Fix external API key\",\"modelUsage\":{}}";
    var r = ClaudeJson.parse(new ContainerResult(0, json, "", false, "id"), null);
    assertThat(r.error()).isTrue();
    assertThat(r.resultText()).isEqualTo("Invalid API key · Fix external API key");
    assertThat(r.model()).isEqualTo("default");
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
