package org.jwcarman.noir.measure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Any container failure, timeout, or missing infrastructure step is a {@link DockerException},
 * so the judge never sees an incomplete measurement.
 */
public final class ContainerMeasureRunner implements MeasureRunner {
  static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);
  static final Duration ANALYZE_TIMEOUT = Duration.ofMinutes(10);
  static final Duration PIT_TIMEOUT = Duration.ofMinutes(30);
  static final long MAX_LOG_BYTES = 256 * 1024;
  static final long MAX_EXIT_BYTES = 16;
  static final String SCRIPT = "/opt/noir/bin/measure.sh";

  /** Analyze steps that only fail on infrastructure trouble. */
  static final List<String> INFRA_STEPS =
      List.of("clone", "export", "holdout-sources", "changed", "forbidden", "generated");

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

  public ContainerMeasureRunner(ContainerPort docker, NoirConfig config) {
    this.docker = docker;
    this.config = config;
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
    try {
      var env = Map.of("BASE_SHA", spec.baseSha(), "BRANCH", branch);
      var bundle = Mount.ro(attemptBundle, "/work/attempt.bundle");

      phase(
          log,
          "build",
          List.of(bundle, Mount.rw(buildDir, "/work/build")),
          env,
          true,
          BUILD_TIMEOUT);

      var analyzeEnv = new HashMap<>(env);
      analyzeEnv.put("STUB_CLASSES", String.join(",", stubClasses(spec)));
      phase(
          log,
          "analyze",
          List.of(
              bundle,
              Mount.ro(buildDir, "/work/build"),
              Mount.ro(stubsDir, "/work/stubs"),
              Mount.rw(outDir, "/work/out")),
          analyzeEnv,
          false,
          ANALYZE_TIMEOUT);

      copyRecord(buildDir.resolve("steps"), outDir, "build");
      copyRecord(buildDir.resolve("steps"), outDir, "deps");
      var a = new MeasureArtifacts(outDir);
      var targets = lines(a.read("target-classes.txt"));
      if (a.exit("build").orElse(1) != 0) {
        skip(outDir, 1, "skipped: the build failed");
      } else if (targets.isEmpty()) {
        skip(outDir, 0, "skipped: no changed classes");
      } else {
        phase(
            log,
            "pit",
            List.of(Mount.ro(buildDir, "/work/build"), Mount.rw(pitDir, "/work/pit")),
            Map.of("TARGET_CLASSES", String.join(",", targets)),
            false,
            PIT_TIMEOUT);
        copyRecord(pitDir, outDir, "pit");
        copyMutations(pitDir, outDir);
      }
      writeStubs(outDir, stubClasses(spec));
    } finally {
      writeLog(outDir, log);
      deleteRecursively(scratch);
    }
    var artifacts = new MeasureArtifacts(outDir);
    requireSteps(artifacts);
    return artifacts;
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

  private void phase(
      StringBuilder log,
      String name,
      List<Mount> mounts,
      Map<String, String> env,
      boolean network,
      Duration timeout) {
    var spec =
        ContainerSpec.of(config.images().builder(), SCRIPT, name)
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
    if (!r.ok()) {
      throw new DockerException(
          "measurement "
              + name
              + " container "
              + (r.timedOut() ? "timed out" : "exited " + r.exitCode())
              + ":\n"
              + r.tail(40));
    }
  }

  /**
   * Copies {@code <step>.log} (its tail) and {@code <step>.exit} from a scratch directory that
   * builder code could write. A missing, symlinked, oversized or malformed record means the step
   * failed: that is the builder's doing, never an infrastructure error.
   */
  static void copyRecord(Path from, Path outDir, String step) {
    var exit = safeFile(from, step + ".exit", MAX_EXIT_BYTES).map(ContainerMeasureRunner::text);
    var logText =
        safeFile(from, step + ".log", Long.MAX_VALUE).map(ContainerMeasureRunner::tail).orElse("");
    if (exit.isEmpty() || !EXIT.matcher(exit.get().strip()).matches()) {
      write(
          outDir,
          step + ".log",
          logText + "\nnoir: the " + step + " step left no valid exit record\n");
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
