package org.jwcarman.noir.agent;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** An {@link AgentPort} that writes predetermined files instead of calling a model. */
public final class ScriptedAgent implements AgentPort {
  private final BiConsumer<Integer, Path> script;
  private final List<AgentRequest> requests = new ArrayList<>();

  /** {@code script} receives the 1-based call number and the request's work dir. */
  public ScriptedAgent(BiConsumer<Integer, Path> script) {
    this.script = script;
  }

  public List<AgentRequest> requests() {
    return requests;
  }

  @Override
  public AgentResult run(AgentRequest request) {
    requests.add(request);
    script.accept(requests.size(), request.workDir());
    return new AgentResult(0, false, false, BigDecimal.ONE, 3, "scripted", "done", "scripted");
  }
}
