package org.jwcarman.noir.deliver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

  public List<String> commitsSince(String baseSha, String branch) {
    var out = git("rev-list", baseSha + ".." + branch).stdout();
    return out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  public String patchSince(String baseSha, String branch) {
    return git(
            "log",
            "-p",
            "--binary",
            "--text",
            "-m",
            "--no-color",
            "--no-ext-diff",
            "--no-textconv",
            baseSha + ".." + branch)
        .stdout();
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
    var argv = new ArrayList<String>(List.of("git", "-C", dir.toString()));
    argv.addAll(List.of(args));
    return run(runner, argv);
  }

  static CommandResult run(CommandRunner runner, List<String> argv) {
    var result = runner.run(Command.of(argv).withEnv(GIT_ENV));
    if (!result.ok()) {
      var detail = result.tail(20);
      var verb = argv.get(argv.indexOf("-C") >= 0 ? argv.indexOf("-C") + 2 : 1);
      throw new GitException("git " + verb + " failed" + (detail.isBlank() ? "" : ": " + detail));
    }
    return result;
  }
}
