package org.jwcarman.noir.seal;

import java.nio.file.Path;
import org.jwcarman.noir.agent.AgentResult;
import org.jwcarman.noir.spec.SpecProblems;

/** One Examiner attempt: the agent's report and where its tests and reference now live. */
public record ExaminerOutput(
    AgentResult agent, Path holdoutDir, Path referenceDir, SpecProblems.Read specProblems) {
  public ExaminerOutput(AgentResult agent, Path holdoutDir, Path referenceDir) {
    this(agent, holdoutDir, referenceDir, new SpecProblems.Absent());
  }
}
