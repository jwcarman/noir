package org.jwcarman.noir.seal;

import java.nio.file.Path;

/** Runs the seal checks and returns their artifacts (judged separately by {@link SealJudge}). */
public interface SealCheckRunner {
  SealArtifacts run(SealInputs inputs, Path outDir);

  /**
   * Lists the repository's libraries and their packages at {@code baseSha} into {@code outDir}
   * (once per seal, before the Examiner runs). A failure is an infrastructure failure, thrown.
   * Runners that only check suites (test fakes) do not support it.
   */
  default RepoInventoryFiles inventory(Path bundle, String baseSha, Path outDir) {
    throw new UnsupportedOperationException("this runner does not build repository inventories");
  }
}
