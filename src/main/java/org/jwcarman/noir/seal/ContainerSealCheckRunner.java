package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.Mount;
import org.jwcarman.noir.spec.ItemId;

/**
 * Runs {@code seal-check.sh} in the builder image, in two phases: {@code prepare} (with network:
 * clone, assumptions, stub build, dependency download) and {@code check} (no network: compile and
 * run the holdout tests, build the reference, mutation testing, and for a modify spec the
 * repository's existing tests against the reference). Holdout tests and the reference never run
 * with network access, matching grading conditions. A third phase, {@code inventory} (with network,
 * the base commit only), lists the repository's libraries and their packages.
 */
public final class ContainerSealCheckRunner implements SealCheckRunner {
  static final int RERUNS = 3;

  /** Working state the script keeps in the output dir; removed afterwards. */
  static final List<String> SCRATCH = List.of("repo", "m2", "deps", "holdout-classes");

  /**
   * The updated tests for prepare are staged in a sibling of the output directory with this suffix,
   * removed as soon as prepare ends.
   */
  static final String UPDATED_TESTS_SUFFIX = "-updated-tests";

  /** Written by the inventory phase, with the reason, when it lists nothing on purpose. */
  static final String INVENTORY_EMPTY = "inventory-empty.txt";

  private final ContainerPort docker;
  private final NoirConfig config;

  public ContainerSealCheckRunner(ContainerPort docker, NoirConfig config) {
    this.docker = docker;
    this.config = config;
  }

  @Override
  public SealArtifacts run(SealInputs in, Path outDir) {
    deleteRecursively(outDir);
    try {
      Files.createDirectories(outDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var env =
        Map.of(
            "BASE_SHA", in.spec().baseSha() == null ? "" : in.spec().baseSha(),
            "HOLDOUT_PKG", ItemId.holdoutPackage(in.spec().id()),
            "TARGET_CLASSES", String.join(",", in.targetClasses()),
            "TEST_TIMEOUT", config.timeouts().perTest().toSeconds() + "s",
            "RERUNS", String.valueOf(RERUNS),
            "EXISTING_TESTS", in.spec().modifies().isEmpty() ? "no" : "yes",
            "EXCLUDE_TESTS", String.join(",", in.spec().testsToUpdate()));
    var log = new StringBuilder();

    var shared =
        List.of(
            Mount.ro(in.bundle(), "/work/base.bundle"),
            Mount.ro(in.stubsDir(), "/work/stubs"),
            Mount.rw(outDir, "/work/out"));
    var updatedTests = outDir.resolveSibling(outDir.getFileName() + UPDATED_TESTS_SUFFIX);
    try {
      stageUpdatedTests(in, updatedTests);
      var prepareMounts = new ArrayList<>(shared);
      prepareMounts.add(Mount.ro(updatedTests, "/work/updated-tests"));
      var prepare = docker.run(spec("prepare", prepareMounts, env, true));
      record(log, "prepare", prepare);
    } finally {
      deleteRecursively(updatedTests);
    }

    var checkMounts = new ArrayList<>(shared);
    checkMounts.add(Mount.ro(in.holdoutDir(), "/work/holdout"));
    checkMounts.add(Mount.ro(in.referenceDir(), "/work/reference"));
    var check = docker.run(spec("check", checkMounts, env, false));
    record(log, "check", check);

    try {
      Files.writeString(outDir.resolve("container.log"), log);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    SCRATCH.forEach(d -> deleteRecursively(outDir.resolve(d)));
    return new SealArtifacts(outDir);
  }

  /**
   * Stages only the reference's updated versions of the spec's tests to update in {@code staged} (a
   * fresh directory beside the output directory): the online prepare phase compiles them with the
   * repository's tests, but never sees the rest of the reference.
   */
  private static void stageUpdatedTests(SealInputs in, Path staged) {
    try {
      deleteRecursively(staged);
      Files.createDirectories(staged);
      for (var path : in.spec().testsToUpdate()) {
        var source = in.referenceDir().resolve(path).normalize();
        if (path.isEmpty()
            || path.startsWith("/")
            || path.contains("..")
            || !source.startsWith(in.referenceDir())
            || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        var target = staged.resolve(path);
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Runs the {@code inventory} phase against the base commit alone (no stubs, no suite). The
   * inventory is empty only for a repository the seal checks reject anyway (no pom.xml at its root,
   * or a multi-module pom), which the script marks with {@value #INVENTORY_EMPTY}. Throws {@link
   * DockerException} when the container timed out, the clone failed, or the listing failed or never
   * finished (Maven could not reach a repository, a dependency did not resolve), so the seal is
   * retried or blocked rather than sealed against an empty inventory.
   */
  @Override
  public RepoInventoryFiles inventory(Path bundle, String baseSha, Path outDir) {
    deleteRecursively(outDir);
    try {
      Files.createDirectories(outDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var mounts = List.of(Mount.ro(bundle, "/work/base.bundle"), Mount.rw(outDir, "/work/out"));
    var result = docker.run(spec("inventory", mounts, Map.of("BASE_SHA", baseSha), true));
    var log = new StringBuilder();
    record(log, "inventory", result);
    try {
      Files.writeString(outDir.resolve("container.log"), log);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    SCRATCH.forEach(d -> deleteRecursively(outDir.resolve(d)));
    var artifacts = new SealArtifacts(outDir);
    if (result.timedOut()) {
      throw inventoryFailed("the container timed out", artifacts, "inventory");
    }
    if (!artifacts.exit("clone").equals(Optional.of(0))) {
      throw inventoryFailed("clone failed", artifacts, "clone");
    }
    var listed = artifacts.exit("inventory");
    if (listed.isEmpty()) {
      throw inventoryFailed("the listing never finished", artifacts, "inventory");
    }
    if (listed.get() != 0) {
      throw inventoryFailed(
          "the listing failed (exit " + listed.get() + ")", artifacts, "inventory");
    }
    if (Files.isRegularFile(outDir.resolve(INVENTORY_EMPTY), LinkOption.NOFOLLOW_LINKS)) {
      // The seal checks reject this repository as they always have: nothing is listed.
      try {
        Files.writeString(outDir.resolve(RepoInventoryFiles.LIBRARIES), "");
        Files.writeString(outDir.resolve(RepoInventoryFiles.PACKAGES), "");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return new RepoInventoryFiles(List.of(), List.of());
    }
    return RepoInventoryFiles.read(outDir);
  }

  private static DockerException inventoryFailed(
      String reason, SealArtifacts artifacts, String step) {
    return new DockerException(
        "repository inventory failed: " + reason + ": " + artifacts.logTail(step, 20));
  }

  private ContainerSpec spec(
      String phase, List<Mount> mounts, Map<String, String> env, boolean network) {
    return ContainerSpec.of(config.images().builder(), "/opt/noir/bin/seal-check.sh", phase)
        .withMounts(mounts)
        .withEnv(env)
        .withNetwork(network)
        .withLimits(config.limits())
        .withTimeout(Duration.ofMinutes(30));
  }

  private static void record(StringBuilder log, String phase, ContainerResult r) {
    log.append("== ").append(phase).append(" (exit ").append(r.exitCode());
    if (r.timedOut()) {
      log.append(", timed out");
    }
    log.append(")\n").append(r.stdout()).append(r.stderr()).append('\n');
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
