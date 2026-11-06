package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/** Writes seal-check artifacts as {@code seal-check.sh} would, for tests. */
public final class SealArtifactsFixture {
  private SealArtifactsFixture() {}

  /** The inventory of a repository with no libraries and no main sources. */
  public static final RepoInventory EMPTY_INVENTORY =
      new RepoInventory(List.of(), Set.of(), Set.of(), Optional.empty(), "");

  /**
   * A runner whose checks are {@code run} and whose inventory lists no libraries (writing the empty
   * listing files, as {@code seal-check.sh inventory} does for such a repository).
   */
  public static SealCheckRunner checks(BiFunction<SealInputs, Path, SealArtifacts> run) {
    return new SealCheckRunner() {
      @Override
      public SealArtifacts run(SealInputs inputs, Path outDir) {
        return run.apply(inputs, outDir);
      }

      @Override
      public RepoInventoryFiles inventory(Path bundle, String baseSha, Path outDir) {
        write(outDir, RepoInventoryFiles.LIBRARIES, "");
        write(outDir, RepoInventoryFiles.PACKAGES, "");
        return RepoInventoryFiles.read(outDir);
      }
    };
  }

  /** A fully passing run. */
  public static SealArtifacts good(Path out) {
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

  /** Like {@link #vacuous(Path)} but the passing test is {@code classname#name}. */
  static SealArtifacts vacuous(Path out, String classname, String name) {
    good(out);
    write(out, "stub-run/TEST-junit-jupiter.xml", suite(true, classname, name));
    return new SealArtifacts(out);
  }

  /** Like {@link #good} but each {@code Class#method} existing test fails with the reference. */
  static SealArtifacts existingTestsFail(Path out, String... tests) {
    good(out);
    write(out, "existing-tests.exit", "1");
    var sb = new StringBuilder("<testsuite>");
    for (var t : tests) {
      var hash = t.indexOf('#');
      sb.append("<testcase classname=\"")
          .append(t, 0, hash)
          .append("\" name=\"")
          .append(t.substring(hash + 1))
          .append("\"><failure message=\"x\"/></testcase>");
    }
    write(out, "existing-tests/TEST-existing.xml", sb.append("</testsuite>").toString());
    return new SealArtifacts(out);
  }

  private static String suite(boolean pass) {
    return suite(pass, "noir.holdout.x.T", "a()");
  }

  private static String suite(boolean pass, String classname, String name) {
    return "<testsuite><testcase classname=\""
        + classname
        + "\" name=\""
        + name
        + "\">"
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
