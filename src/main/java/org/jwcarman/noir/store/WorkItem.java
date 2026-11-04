package org.jwcarman.noir.store;

import org.jwcarman.noir.lifecycle.BlockReason;
import org.jwcarman.noir.lifecycle.State;

/** Current state of a sealed work item, persisted as {@code workitem.json}. */
public record WorkItem(
        String id,
        String repo,
        Integer issueNumber,
        State state,
        int attempt,
        int reworkCount,
        BlockReason blockReason,
        State blockedPhase) {

    public static WorkItem sealed(String id, String repo, int issueNumber) {
        return new WorkItem(id, repo, issueNumber, State.SEALED, 0, 0, null, null);
    }

    public WorkItem withState(State v) {
        return new WorkItem(id, repo, issueNumber, v, attempt, reworkCount, blockReason, blockedPhase);
    }

    public WorkItem withAttempt(int v) {
        return new WorkItem(id, repo, issueNumber, state, v, reworkCount, blockReason, blockedPhase);
    }

    public WorkItem withReworkCount(int v) {
        return new WorkItem(id, repo, issueNumber, state, attempt, v, blockReason, blockedPhase);
    }

    public WorkItem withBlock(BlockReason reason, State phase) {
        return new WorkItem(id, repo, issueNumber, state, attempt, reworkCount, reason, phase);
    }

    public WorkItem withoutBlock() {
        return withBlock(null, null);
    }
}
