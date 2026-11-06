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
  STOP_CONTAINERS,
  /** Comment and remove {@code noir:ready} from the issue. */
  REJECT_APPROVAL,
  /** Move the item's attempts aside ({@code runs/<n>/}) before a re-approved item starts over. */
  ARCHIVE_ATTEMPTS
}
