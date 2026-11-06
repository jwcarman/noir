package org.jwcarman.noir.deliver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.CommandRunner;
import org.jwcarman.noir.git.GitException;

/**
 * A fresh bare repository created by noir and populated only from a builder-produced git bundle.
 * The builder's own repository (which may carry hostile hooks or config) is never touched.
 */
public final class BundleRepo implements AutoCloseable {
  static final Map<String, String> GIT_ENV =
      Map.of(
          "GIT_TERMINAL_PROMPT", "0",
          "GIT_CONFIG_NOSYSTEM", "1",
          "GIT_CONFIG_GLOBAL", "/dev/null");

  /** The most patch output noir reads; a larger patch is refused, never scanned in part. */
  public static final long MAX_PATCH_BYTES = 256L * 1024 * 1024;

  private final CommandRunner runner;
  private final Path dir;

  private BundleRepo(CommandRunner runner, Path dir) {
    this.runner = runner;
    this.dir = dir;
  }

  public static BundleRepo open(CommandRunner runner, Path bundle, Path tempRoot) {
    Path dir;
    try {
      Files.createDirectories(tempRoot);
      dir = Files.createTempDirectory(tempRoot, "noir-bundle-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var repo = new BundleRepo(runner, dir);
    try {
      var copy = copyBundle(bundle, dir);
      run(runner, List.of("git", "init", "--bare", "-q", dir.toString()));
      repo.git("fetch", "-q", copy.toString(), "+refs/heads/*:refs/heads/*");
      return repo;
    } catch (RuntimeException e) {
      repo.close();
      throw e;
    }
  }

  /** Copies a regular (non-symlink) bundle file into the temp dir and checks its header. */
  private static Path copyBundle(Path bundle, Path dir) {
    if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
      throw new GitException("Not a regular bundle file: " + bundle);
    }
    var copy = dir.resolve("input.bundle");
    try (var in = Files.newInputStream(bundle, LinkOption.NOFOLLOW_LINKS)) {
      Files.copy(in, copy);
    } catch (IOException e) {
      throw new GitException("Cannot read bundle " + bundle + ": " + e.getMessage());
    }
    String first;
    try (var lines = Files.lines(copy, java.nio.charset.StandardCharsets.ISO_8859_1)) {
      first = lines.findFirst().orElse("");
    } catch (IOException | java.io.UncheckedIOException e) {
      first = "";
    }
    if (!first.equals("# v2 git bundle") && !first.equals("# v3 git bundle")) {
      throw new GitException("Not a git bundle: " + bundle);
    }
    return copy;
  }

  /** The full SHA of {@code branch}. */
  public String tip(String branch) {
    return git("rev-parse", "--verify", "refs/heads/" + branch).stdout().trim();
  }

  public List<String> commitsSince(String baseSha, String branch) {
    var out = git("rev-list", baseSha + ".." + branch).stdout();
    return out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /**
   * The full patch beyond {@code baseSha}. Output over {@link #MAX_PATCH_BYTES} is refused with
   * {@link PatchTooLargeException}: a truncated patch is never returned (or scanned).
   */
  public String patchSince(String baseSha, String branch) {
    var result =
        git(
            MAX_PATCH_BYTES,
            "log",
            "-p",
            "--binary",
            "--text",
            "-m",
            "--no-color",
            "--no-ext-diff",
            "--no-textconv",
            baseSha + ".." + branch);
    if (result.truncated()) {
      throw new PatchTooLargeException(
          "patch " + baseSha + ".." + branch + " exceeds " + MAX_PATCH_BYTES + " bytes");
    }
    return result.stdout();
  }

  /** Files changed beyond {@code baseSha}, joined from NUL-separated name-status and numstat. */
  public List<FileChange> fileChanges(String baseSha, String branch) {
    var range = baseSha + ".." + branch;
    var status = diffOutput("--name-status", range).split("\0");
    var numstat = diffOutput("--numstat", range).split("\0");
    var counts = new HashMap<String, int[]>();
    for (int i = 0; i < numstat.length; i++) {
      var tok = numstat[i];
      if (tok.isEmpty()) {
        continue;
      }
      var f = tok.split("\t", 3);
      var path = f[2];
      if (path.isEmpty()) { // rename: "a\td\t" then old, new
        path = numstat[i + 2];
        i += 2;
      }
      counts.put(path, new int[] {count(f[0]), count(f[1])});
    }
    var changes = new ArrayList<FileChange>();
    for (int i = 0; i < status.length; i++) {
      var code = status[i];
      if (code.isEmpty()) {
        continue;
      }
      var kind = code.charAt(0);
      String oldPath = null;
      var path = status[++i];
      if (kind == 'R') {
        oldPath = path;
        path = status[++i];
      }
      var c = counts.getOrDefault(path, new int[] {0, 0});
      var st =
          switch (kind) {
            case 'A' -> FileChange.Status.ADDED;
            case 'D' -> FileChange.Status.DELETED;
            case 'R' -> FileChange.Status.RENAMED;
            default -> FileChange.Status.MODIFIED;
          };
      changes.add(new FileChange(st, path, kind == 'R' ? oldPath : null, c[0], c[1]));
    }
    changes.sort(Comparator.comparing(FileChange::path));
    return List.copyOf(changes);
  }

  private String diffOutput(String format, String range) {
    var result = git("diff", "-z", "-M", format, "--no-color", "--no-ext-diff", range);
    if (result.truncated()) {
      throw new GitException("git diff output for " + range + " was truncated");
    }
    return result.stdout();
  }

  private static int count(String s) {
    return s.equals("-") ? 0 : Integer.parseInt(s);
  }

  public Path dir() {
    return dir;
  }

  @Override
  public void close() {
    try (var walk = Files.walk(dir)) {
      for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (java.nio.file.NoSuchFileException e) {
      // already gone
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Runs git against this repo with the hardened environment; extra leading args allowed. */
  CommandResult git(String... args) {
    return git(Command.DEFAULT_OUTPUT_LIMIT, args);
  }

  private CommandResult git(long outputLimit, String... args) {
    var argv = new ArrayList<String>(List.of("git", "-C", dir.toString()));
    argv.addAll(List.of(args));
    return run(runner, argv, outputLimit);
  }

  static CommandResult run(CommandRunner runner, List<String> argv) {
    return run(runner, argv, Command.DEFAULT_OUTPUT_LIMIT);
  }

  private static CommandResult run(CommandRunner runner, List<String> argv, long outputLimit) {
    var result = runner.run(Command.of(argv).withEnv(GIT_ENV).withOutputLimit(outputLimit));
    if (!result.ok()) {
      var detail = result.tail(20);
      var verb = argv.get(argv.indexOf("-C") >= 0 ? argv.indexOf("-C") + 2 : 1);
      throw new GitException("git " + verb + " failed" + (detail.isBlank() ? "" : ": " + detail));
    }
    return result;
  }
}
