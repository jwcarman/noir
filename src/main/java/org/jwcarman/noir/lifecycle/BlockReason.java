package org.jwcarman.noir.lifecycle;

/** Why a work item is BLOCKED. */
public enum BlockReason {
  INFRASTRUCTURE,
  SECRET_FOUND,
  HOLDOUT_DEFECT
}
