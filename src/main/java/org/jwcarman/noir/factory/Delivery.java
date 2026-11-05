package org.jwcarman.noir.factory;

import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.store.WorkItem;

/** Where finished work goes: pull requests, issue comments and labels. */
public interface Delivery {
  /** Publishes the attempt's branch and opens (or reuses) its pull request; returns its number. */
  int openPullRequest(WorkItem item, Spec spec, AttemptPaths attempt, String body, boolean draft);

  void comment(WorkItem item, String body);

  /** Mirrors {@code state} as the issue's {@code noir:*} label. */
  void label(WorkItem item, State state);

  /**
   * Tells the approver why the issue was not sealed: posts {@code comment}, then removes {@code
   * noir:ready} so a human can approve the issue again.
   */
  void rejectApproval(WorkItem item, String comment);
}
