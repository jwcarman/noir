package org.jwcarman.noir.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.json.Json;

/** Parses the result object printed by {@code claude -p --output-format json}. */
public final class ClaudeJson {
  private ClaudeJson() {}

  public static AgentResult parse(ContainerResult r, String requestedModel) {
    var model = requestedModel == null ? "default" : requestedModel;
    var node = resultObject(r.stdout());
    if (node == null) {
      return new AgentResult(
          r.exitCode(), true, r.timedOut(), BigDecimal.ZERO, 0, model, r.tail(40), r.imageId());
    }
    var usage = node.path("modelUsage");
    if (usage.isObject() && usage.fieldNames().hasNext()) {
      model = usage.fieldNames().next();
    }
    var cost = node.path("total_cost_usd");
    return new AgentResult(
        r.exitCode(),
        node.path("is_error").asBoolean(false) || !r.ok(),
        r.timedOut(),
        cost.isNumber() ? new BigDecimal(cost.asText()) : BigDecimal.ZERO,
        node.path("num_turns").asInt(0),
        model,
        node.path("result").asText(""),
        r.imageId());
  }

  private static JsonNode resultObject(String stdout) {
    var lines = stdout.strip().split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      var line = lines[i].strip();
      if (line.startsWith("{")) {
        try {
          return Json.mapper().readTree(line);
        } catch (IOException e) {
          return null;
        }
      }
    }
    return null;
  }
}
