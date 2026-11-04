package org.jwcarman.noir.factory;

import org.jwcarman.noir.deliver.BranchPublisher;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.store.WorkItem;

/** {@link Delivery} to GitHub: the branch through {@link BranchPublisher}, the rest through gh. */
public final class GitHubDelivery implements Delivery {
  private final GitHubPort gh;
  private final BranchPublisher publisher;

  public GitHubDelivery(GitHubPort gh, BranchPublisher publisher) {
    this.gh = gh;
    this.publisher = publisher;
  }

  @Override
  public int openPullRequest(
      WorkItem item, Spec spec, AttemptPaths attempt, String body, boolean draft) {
    var branch = BranchNames.of(item.issueNumber(), spec.title());
    publisher.push(item.repo(), attempt.bundle(), branch);
    return gh.findPullRequest(item.repo(), branch)
        .orElseGet(() ->
            gh.createPullRequest(
                item.repo(), gh.defaultBranch(item.repo()), branch, spec.title(), body, draft));
  }

  @Override
  public void comment(WorkItem item, String body) {
    gh.comment(item.repo(), item.issueNumber(), body);
  }

  @Override
  public void label(WorkItem item, State state) {
    gh.setNoirLabel(item.repo(), item.issueNumber(), state.label());
  }
}
