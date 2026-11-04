package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes seal-check artifacts as {@code seal-check.sh} would, for tests. */
final class SealArtifactsFixture {
  private SealArtifactsFixture() {}

  /** A fully passing run. */
  static SealArtifacts good(Path out) {
    write(out, "clone.exit", "0");
    write(out, "assumptions.exit", "0");
    write(out, "pom.txt", "yes");
    write(out, "modules.txt", "no");
    write(out, "release.txt", "21");
    write(out, "stub-build.exit", "0");
    write(out, "holdout-compile.exit", "0");
    write(out, "stub-run.exit", "1");
    write(out, "stub-run/TEST-junit-jupiter.xml", suite(false));
    write(out, "ref-build.exit", "0");
    for (int n = 1; n <= 3; n++) {
      write(out, "ref-run-" + n + ".exit", "0");
      write(out, "ref-run-" + n + "/TEST-junit-jupiter.xml", suite(true));
    }
    write(out, "pit.exit", "0");
    write(out, "pit/mutations.xml", "<mutations><mutation detected='true'/></mutations>");
    return new SealArtifacts(out);
  }

  /** Like {@link #good} but the test passes against the stubs. */
  static SealArtifacts vacuous(Path out) {
    good(out);
    write(out, "stub-run/TEST-junit-jupiter.xml", suite(true));
    return new SealArtifacts(out);
  }

  private static String suite(boolean pass) {
    return "<testsuite><testcase classname=\"noir.holdout.x.T\" name=\"a()\">"
        + (pass ? "" : "<failure message=\"x\"/>")
        + "</testcase></testsuite>";
  }

  private static void write(Path out, String rel, String content) {
    try {
      var p = out.resolve(rel);
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
