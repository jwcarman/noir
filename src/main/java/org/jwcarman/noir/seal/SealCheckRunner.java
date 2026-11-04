package org.jwcarman.noir.seal;

import java.nio.file.Path;

/** Runs the seal checks and returns their artifacts (judged separately by {@link SealJudge}). */
public interface SealCheckRunner {
  SealArtifacts run(SealInputs inputs, Path outDir);
}
