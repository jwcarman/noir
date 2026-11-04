package org.jwcarman.noir.seal;

/** Sealing progress persisted in the draft as {@code seal.json}, so a re-run never duplicates. */
public record SealProgress(Integer issueNumber, boolean commented) {
  static final SealProgress NONE = new SealProgress(null, false);
}
