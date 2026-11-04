package org.jwcarman.noir.measure;

import java.nio.file.Path;
import org.jwcarman.noir.spec.Spec;

/** Rebuilds an attempt's branch in clean containers and writes the artifacts the judge reads. */
public interface MeasureRunner {
  MeasureArtifacts run(Spec spec, Path attemptBundle, String branch, Path stubsDir, Path outDir);
}
