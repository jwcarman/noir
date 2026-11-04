package org.jwcarman.noir.build;

import java.nio.file.Path;
import org.jwcarman.noir.agent.AgentResult;

/**
 * The result of one builder attempt: the branch bundle and whether this attempt added a commit
 * beyond its input bundle's branch head (earlier attempts' commits do not count).
 */
public record BuildOutcome(Path bundle, boolean producedCommits, AgentResult agent) {}
