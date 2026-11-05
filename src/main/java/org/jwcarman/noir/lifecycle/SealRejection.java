package org.jwcarman.noir.lifecycle;

/** Why a seal attempt was rejected and the item returned to {@code DRAFT}. */
public enum SealRejection {
  EDITED_AFTER_APPROVAL,
  INVALID_SPEC,
  SUITE_TOO_WEAK,
  STUB_EXISTS;

  /** The value recorded on the {@code transition} event. */
  public String eventValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
