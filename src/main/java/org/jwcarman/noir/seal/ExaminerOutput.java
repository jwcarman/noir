package org.jwcarman.noir.seal;

import java.nio.file.Path;
import org.jwcarman.noir.agent.AgentResult;

/** One Examiner attempt: the agent's report and where its tests and reference now live. */
public record ExaminerOutput(AgentResult agent, Path holdoutDir, Path referenceDir) {}
