package org.jwcarman.noir.measure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.docker.Mount;
import org.jwcarman.noir.spec.Spec;

/**
 * Measures an attempt with {@code measure.sh} in the builder image, in three fresh containers (see
 * the script header for the trust argument): {@code build} (network, runs builder code, writes only
 * scratch), {@code analyze} (no network, no builder code, writes the judged files) and {@code pit}
 * (no network, runs builder tests, writes only scratch). No container gets credentials. The host
 * then copies the builder-phase step records and PIT's report into the output directory with
 * symlink, size and format checks, and writes {@code stubs.txt} from the spec.
 *
 * <p>Builder faults are builder failures: a build or PIT step that hangs, crashes, or dies with its
 * container is recorded as that step's failure (exit 1, with the container's log tail), which the
 * judge reports as a build or mutation-testing failure. Failures of noir-only work (the analyze
 * container, its infrastructure steps, missing judged files) are a {@link DockerException}, so the
 * judge never sees an incomplete measurement.
 */
public final class ContainerMeasureRunner implements MeasureRunner, BaselineRunner {
  static final int LOG_TAIL_LINES = 80;
  static final long MAX_LOG_BYTES = 256 * 1024;
  static final long MAX_EXIT_BYTES = 16;
  static final String SCRIPT = "/opt/noir/bin/measure.sh";

  /** Analyze steps that only fail on infrastructure trouble. */
  static final List<String> INFRA_STEPS =
      List.of(
          "clone",
          "export",
          "holdout-sources",
          "changed",
          "forbidden",
          "generated",
          "test-reports");

  /** Steps whose exit the judge requires. */
  static final List<String> STEPS =
      List.of(
          "build",
          "deps",
          "clone",
          "export",
          "holdout-sources",
          "changed",
          "forbidden",
          "generated",
          "api",
          "coverage",
          "test-reports",
          "pit");

  static final List<String> FILES =
      List.of(
          "holdout-sources.txt",
          "forbidden.txt",
          "changed.txt",
          "generated.txt",
          "target-classes.txt",
          "javap-stub.txt",
          "javap-built.txt",
          "stubs.txt");

  private static final Pattern EXIT = Pattern.compile("-?\\d{1,5}");

  private final ContainerPort docker;
  private final NoirConfig config;
  private final Timeouts timeouts;

  public ContainerMeasureRunner(ContainerPort docker, NoirConfig config) {
    this(docker, config, Timeouts.DEFAULT);
  }

  ContainerMeasureRunner(ContainerPort docker, NoirConfig config, Timeouts timeouts) {
    this.docker = docker;
    this.config = config;
    this.timeouts = timeouts;
  }

  /**
   * Time limits. {@code buildStep} bounds each builder-code step of the build phase (build, deps)
   * and {@code pitStep} the PIT run; the script enforces them with {@code timeout}, so a hanging
   * builder test is a recorded step failure. The containers get extra headroom on top.
   */
  record Timeouts(Duration buildStep, Duration pitStep, Duration analyze) {
    static final Timeouts DEFAULT =
        new Timeouts(Duration.ofMinutes(20), Duration.ofMinutes(25), Duration.ofMinutes(10));
    static final Duration HEADROOM = Duration.ofMinutes(5);

    Duration buildContainer() {
      return buildStep.multipliedBy(2).plus(HEADROOM);
    }

    Duration pitContainer() {
      return pitStep.plus(HEADROOM);
    }
  }

  @Override
  public MeasureArtifacts run(
      Spec spec, Path attemptBundle, String branch, Path stubsDir, Path outDir) {
    if (spec.baseSha() == null) {
      throw new IllegalArgumentException("spec " + spec.id() + " has no baseSha");
    }
    var scratch = outDir.resolveSibling(outDir.getFileName() + ".scratch");
    var buildDir = scratch.resolve("build");
    var pitDir = scratch.resolve("pit");
    deleteRecursively(outDir);
    deleteRecursively(scratch);
    try {
      Files.createDirectories(outDir);
      Files.createDirectories(buildDir);
      Files.createDirectories(pitDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var log = new StringBuilder();
    Throwable primary = null;
    try {
      var env = Map.of("BASE_SHA", spec.baseSha(), "BRANCH", branch);
      var bundle = Mount.ro(attemptBundle, "/work/attempt.bundle");

      var buildEnv = new HashMap<>(env);
      buildEnv.put("STEP_TIMEOUT", String.valueOf(timeouts.buildStep().toSeconds()));
      var build =
          phase(
              log,
              spec.id(),
              "build",
              List.of(bundle, Mount.rw(buildDir, "/work/build")),
              buildEnv,
              true,
              timeouts.buildContainer());

      var analyzeEnv = new HashMap<>(env);
      analyzeEnv.put("STUB_CLASSES", String.join(",", stubClasses(spec)));
      requireOk(
          "analyze",
          phase(
              log,
              spec.id(),
              "analyze",
              List.of(
                  bundle,
                  Mount.ro(buildDir, "/work/build"),
                  Mount.ro(stubsDir, "/work/stubs"),
                  Mount.rw(outDir, "/work/out")),
              analyzeEnv,
              false,
              timeouts.analyze()));

      var buildFailure = failure("build", build);
      copyRecord(buildDir.resolve("steps"), outDir, "build", buildFailure);
      copyRecord(buildDir.resolve("steps"), outDir, "deps", buildFailure);
      if (buildFailure != null) {
        failStep(outDir, "build", buildFailure);
      }
      failBuildOnDeps(outDir);
      var a = new MeasureArtifacts(outDir);
      var targets = lines(a.read("target-classes.txt"));
      if (a.exit("build").orElse(1) != 0) {
        skip(outDir, 1, "skipped: the build failed");
      } else if (targets.isEmpty()) {
        skip(outDir, 0, "skipped: no changed classes");
      } else {
        var pit =
            phase(
                log,
                spec.id(),
                "pit",
                List.of(Mount.ro(buildDir, "/work/build"), Mount.rw(pitDir, "/work/pit")),
                Map.of(
                    "TARGET_CLASSES",
                    String.join(",", targets),
                    "STEP_TIMEOUT",
                    String.valueOf(timeouts.pitStep().toSeconds())),
                false,
                timeouts.pitContainer());
        var pitFailure = failure("pit", pit);
        copyRecord(pitDir, outDir, "pit", pitFailure);
        if (pitFailure != null) {
          failStep(outDir, "pit", pitFailure);
        }
        copyMutations(pitDir, outDir);
      }
      writeStubs(outDir, stubClasses(spec));
    } catch (RuntimeException | Error e) {
      primary = e;
      throw e;
    } finally {
      cleanup(outDir, log, scratch, primary);
    }
    var artifacts = new MeasureArtifacts(outDir);
    requireSteps(artifacts);
    return artifacts;
  }

  /**
   * Runs {@code measure.sh baseline} on the item's base bundle: the base commit is code a human
   * approved, never builder code, so its container writes the output directory directly. A base
   * build that fails is returned (with its log tail); a container, clone or report-copy failure is
   * infrastructure.
   */
  @Override
  public Baseline baseline(String itemId, Path baseBundle, String branch, Path outDir) {
    deleteRecursively(outDir);
    try {
      Files.createDirectories(outDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var log = new StringBuilder();
    try {
      requireOk(
          "baseline",
          phase(
              log,
              itemId,
              "baseline",
              List.of(Mount.ro(baseBundle, "/work/attempt.bundle"), Mount.rw(outDir, "/work/out")),
              Map.of(
                  "BRANCH",
                  branch,
                  "STEP_TIMEOUT",
                  String.valueOf(timeouts.buildStep().toSeconds())),
              true,
              timeouts.buildContainer()));
    } finally {
      writeLog(outDir, log);
    }
    var a = new MeasureArtifacts(outDir);
    for (var step : List.of("clone", "test-reports")) {
      var exit = a.exit(step);
      if (exit.isEmpty() || exit.get() != 0) {
        throw new DockerException(
            "baseline step " + step + " did not complete:\n" + a.logTail(step, 40));
      }
    }
    var exit =
        a.exit("baseline")
            .orElseThrow(() -> new DockerException("baseline recorded no build exit"));
    return new Baseline(exit, a.testOutcomes(), a.logTail("baseline", LOG_TAIL_LINES));
  }

  /**
   * Writes the container log and removes scratch. A cleanup failure never masks the error that
   * ended the run: it is added to that error as suppressed.
   */
  private static void cleanup(Path outDir, StringBuilder log, Path scratch, Throwable primary) {
    RuntimeException failure = null;
    for (Runnable step :
        List.<Runnable>of(() -> writeLog(outDir, log), () -> deleteRecursively(scratch))) {
      try {
        step.run();
      } catch (RuntimeException e) {
        if (primary != null) {
          primary.addSuppressed(e);
        } else if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Stub FQNs from the spec's stub paths ({@code src/main/java/a/b/C.java} → {@code a.b.C}). */
  static List<String> stubClasses(Spec spec) {
    var classes = new ArrayList<String>();
    for (var path : spec.apiStubs()) {
      var p = path.replace('\\', '/');
      int root = p.indexOf("src/main/java/");
      if (root >= 0) {
        p = p.substring(root + "src/main/java/".length());
      }
      if (p.endsWith(".java")) {
        classes.add(p.substring(0, p.length() - ".java".length()).replace('/', '.'));
      }
    }
    return classes;
  }

  private ContainerResult phase(
      StringBuilder log,
      String itemId,
      String name,
      List<Mount> mounts,
      Map<String, String> env,
      boolean network,
      Duration timeout) {
    var spec =
        ContainerSpec.of(config.images().builder(), SCRIPT, name)
            .withItem(itemId)
            .withMounts(mounts)
            .withEnv(env)
            .withNetwork(network)
            .withLimits(config.limits())
            .withTimeout(timeout);
    ContainerResult r = docker.run(spec);
    log.append("== ").append(name).append(" (exit ").append(r.exitCode());
    if (r.timedOut()) {
      log.append(", timed out");
    }
    log.append(")\n").append(r.stdout()).append(r.stderr()).append('\n');
    return r;
  }

  /** The analyze phase runs only noir code: any container failure is infrastructure. */
  private static void requireOk(String name, ContainerResult r) {
    var failure = failure(name, r);
    if (failure != null) {
      throw new DockerException("measurement " + failure);
    }
  }

  /** A description of a failed container with its log tail, or null if it succeeded. */
  private static String failure(String name, ContainerResult r) {
    if (r.ok()) {
      return null;
    }
    return name
        + " container "
        + (r.timedOut() ? "timed out" : "exited " + r.exitCode())
        + ":\n"
        + r.tail(LOG_TAIL_LINES);
  }

  /**
   * A builder-code container (build, pit) that died or timed out is the builder's failure, not
   * infrastructure: any step it left without a valid record already reads exit 1; this also fails a
   * step that recorded success, since its container did not finish cleanly.
   */
  private static void failStep(Path outDir, String step, String failure) {
    var a = new MeasureArtifacts(outDir);
    if (a.exit(step).orElse(1) == 0) {
      write(outDir, step + ".log", a.read(step + ".log") + "\nnoir: " + failure + "\n");
      write(outDir, step + ".exit", "1\n");
    }
  }

  /**
   * Copying the build's dependencies runs the builder's POM, so its failure after a successful
   * build is the builder's build failure (with the deps log), not an infrastructure error.
   */
  private static void failBuildOnDeps(Path outDir) {
    var a = new MeasureArtifacts(outDir);
    int deps = a.exit("deps").orElse(1);
    if (a.exit("build").orElse(1) == 0 && deps != 0) {
      write(
          outDir,
          "build.log",
          a.read("build.log")
              + "\nnoir: copying the build's dependencies failed (exit "
              + deps
              + "):\n"
              + a.logTail("deps", LOG_TAIL_LINES)
              + "\n");
      write(outDir, "build.exit", "1\n");
    }
  }

  /**
   * Copies {@code <step>.log} (its tail) and {@code <step>.exit} from a scratch directory that
   * builder code could write. A missing, symlinked, oversized or malformed record means the step
   * failed: that is the builder's doing, never an infrastructure error. {@code containerFailure}
   * (or null) explains a container that died before the step could record anything.
   */
  static void copyRecord(Path from, Path outDir, String step, String containerFailure) {
    var exit = safeFile(from, step + ".exit", MAX_EXIT_BYTES).map(ContainerMeasureRunner::text);
    var logText =
        safeFile(from, step + ".log", Long.MAX_VALUE).map(ContainerMeasureRunner::tail).orElse("");
    if (exit.isEmpty() || !EXIT.matcher(exit.get().strip()).matches()) {
      var why =
          containerFailure != null
              ? containerFailure
              : "the " + step + " step left no valid exit record";
      write(outDir, step + ".log", logText + "\nnoir: " + why + "\n");
      write(outDir, step + ".exit", "1\n");
      return;
    }
    write(outDir, step + ".log", logText);
    write(outDir, step + ".exit", exit.get().strip() + "\n");
  }

  /** Copies PIT's mutations.xml; a successful PIT run without a usable report counts as failed. */
  static void copyMutations(Path pitDir, Path outDir) {
    var report = safeFile(pitDir, "report/mutations.xml", MeasureArtifacts.MAX_REPORT_BYTES);
    if (report.isPresent()) {
      try (InputStream in = Files.newInputStream(report.get(), LinkOption.NOFOLLOW_LINKS)) {
        Files.copy(in, outDir.resolve("mutations.xml"));
        return;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    var a = new MeasureArtifacts(outDir);
    if (a.exit("pit").orElse(1) == 0) {
      write(outDir, "pit.log", a.read("pit.log") + "\nnoir: PIT wrote no usable mutations.xml\n");
      write(outDir, "pit.exit", "1\n");
    }
  }

  private static void skip(Path outDir, int exit, String why) {
    write(outDir, "pit.log", why + "\n");
    write(outDir, "pit.exit", exit + "\n");
  }

  private static void writeStubs(Path outDir, List<String> classes) {
    var text = classes.isEmpty() ? "" : String.join("\n", classes) + "\n";
    write(outDir, "stubs.txt", text);
  }

  /**
   * Every step must have recorded an exit; the infrastructure steps must have exited 0 and written
   * their files. Builder-caused failures (build, api, coverage, pit) are left to the judge.
   */
  static void requireSteps(MeasureArtifacts a) {
    for (var step : STEPS) {
      if (a.exit(step).isEmpty()) {
        throw new DockerException("measurement did not complete step " + step);
      }
    }
    for (var step : INFRA_STEPS) {
      int exit = a.exit(step).orElseThrow();
      if (exit != 0) {
        throw new DockerException(
            "measurement step " + step + " exited " + exit + ":\n" + a.logTail(step, 40));
      }
    }
    if (a.exit("build").orElseThrow() == 0 && a.exit("deps").orElseThrow() != 0) {
      throw new DockerException(
          "measurement step deps exited "
              + a.exit("deps").orElseThrow()
              + ":\n"
              + a.logTail("deps", 40));
    }
    for (var file : FILES) {
      try {
        a.require(file);
      } catch (IllegalStateException e) {
        throw new DockerException("measurement wrote no usable " + file);
      }
    }
    for (var dir : List.of(a.exportClasses(), a.exportDeps())) {
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        throw new DockerException("measurement wrote no " + a.outDir().relativize(dir));
      }
    }
    boolean changed = !lines(a.read("target-classes.txt")).isEmpty();
    if (changed
        && a.exit("build").orElseThrow() == 0
        && a.exit("coverage").orElseThrow() == 0
        && a.report("jacoco.xml").isEmpty()) {
      throw new DockerException("measurement coverage step wrote no jacoco.xml");
    }
  }

  /**
   * {@code root/relative} if every path component below {@code root} is a real directory (no
   * symlinks) and the file is a regular file no larger than {@code max}.
   */
  static Optional<Path> safeFile(Path root, String relative, long max) {
    var p = root;
    var parts = Arrays.asList(relative.split("/"));
    try {
      for (int i = 0; i < parts.size(); i++) {
        p = p.resolve(parts.get(i));
        boolean last = i == parts.size() - 1;
        if (last
            ? !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) > max
            : !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
          return Optional.empty();
        }
      }
      return Optional.of(p);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static String text(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  /** The last {@link #MAX_LOG_BYTES} of a log, decoded leniently. */
  private static String tail(Path p) {
    try (var ch = Files.newByteChannel(p, LinkOption.NOFOLLOW_LINKS)) {
      long size = ch.size();
      long start = Math.max(0, size - MAX_LOG_BYTES);
      ch.position(start);
      var buf = java.nio.ByteBuffer.allocate((int) (size - start));
      while (buf.hasRemaining() && ch.read(buf) >= 0) {
        // read fully
      }
      return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  private static void write(Path outDir, String name, String content) {
    try {
      var p = outDir.resolve(name);
      Files.deleteIfExists(p);
      Files.writeString(p, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeLog(Path outDir, StringBuilder log) {
    if (Files.isDirectory(outDir, LinkOption.NOFOLLOW_LINKS)) {
      write(outDir, "container.log", log.toString());
    }
  }

  private static List<String> lines(String text) {
    return text.isBlank()
        ? List.of()
        : text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }

  /**
   * Deletes a tree the builder's code wrote into, which may hold directories it made unreadable or
   * unwritable: each directory is first given owner rwx, then emptied. Links are never followed (a
   * symlink is deleted, not its target), and the walk is iterative, so depth cannot overflow the
   * stack.
   */
  private static void deleteRecursively(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    var stack = new ArrayDeque<Path>();
    stack.push(root);
    try {
      while (!stack.isEmpty()) {
        var p = stack.peek();
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
          Files.delete(p);
          stack.pop();
          continue;
        }
        // p is a real directory (checked without following links), and no container that could
        // swap it for a link is running any more. (A NOFOLLOW attribute view cannot chmod a
        // directory on macOS.)
        var perms = EnumSet.copyOf(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
        if (perms.addAll(OWNER_RWX)) {
          Files.setPosixFilePermissions(p, perms);
        }
        var children = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(p)) {
          entries.forEach(children::add);
        }
        if (children.isEmpty()) {
          Files.delete(p);
          stack.pop();
        } else {
          children.forEach(stack::push);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final Set<PosixFilePermission> OWNER_RWX =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);
}
