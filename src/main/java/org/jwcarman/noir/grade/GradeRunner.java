package org.jwcarman.noir.grade;

import java.nio.file.Path;

/** Runs the sealed holdout tests against the builder's compiled classes. */
public interface GradeRunner {
  GradeArtifacts run(
      Path classesDir, Path depsDir, Path holdoutDir, String holdoutPackage, Path outDir);
}
