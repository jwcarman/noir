package org.jwcarman.noir.build;

import java.nio.file.Path;
import org.jwcarman.noir.agent.AgentResult;

/** The result of one builder attempt: the branch bundle and whether it has commits beyond base. */
public record BuildOutcome(Path bundle, boolean producedCommits, AgentResult agent) {}
