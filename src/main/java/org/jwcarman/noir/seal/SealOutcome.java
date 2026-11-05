package org.jwcarman.noir.seal;

import org.jwcarman.noir.lifecycle.SealRejection;

/** Result of sealing an approved issue on the server. */
public sealed interface SealOutcome {
  /** The suite is sealed and the commitment posted. */
  record Passed(String commitmentSha256) implements SealOutcome {}

  /** The approval is rejected; {@code comment} is the (secret-masked) text for the issue. */
  record Rejected(SealRejection reason, String comment) implements SealOutcome {}
}
