package org.jwcarman.noir.lifecycle;

/** Actions the orchestrator performs as a consequence of a transition. */
public enum SideEffect {
  APPLY_LABEL,
  START_ATTEMPT,
  EXTRACT_BUNDLE,
  EXPORT_ARTIFACTS,
  WRITE_FEEDBACK,
  OPEN_PR,
  OPEN_DRAFT_PR_OR_COMMENT,
  STOP_CONTAINERS
}
