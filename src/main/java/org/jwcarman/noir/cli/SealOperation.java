package org.jwcarman.noir.cli;

import java.nio.file.Path;
import org.jwcarman.noir.seal.SealResult;

/** Seals a draft from a local clone. The seam between the CLI and {@link Wiring}. */
@FunctionalInterface
interface SealOperation {
  SealResult seal(String draftId, Path repoDir);
}
