package org.jwcarman.noir.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;

/** Runs one builder attempt against a sealed spec. */
public interface BuildRunner {
  /**
   * Builds on {@code branch}, starting from {@code inputBundle} (the item's base bundle on the
   * first attempt, else the previous attempt's bundle), with {@code attemptDir} as scratch and
   * output directory. The attempt's work is committed with the one-line {@code commitMessage} (see
   * {@link CommitMessage}).
   */
  BuildOutcome run(
      Spec spec,
      List<StubSource> stubs,
      Path inputBundle,
      String branch,
      String commitMessage,
      Optional<String> feedback,
      Path attemptDir);
}
