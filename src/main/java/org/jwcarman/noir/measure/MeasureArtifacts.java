package org.jwcarman.noir.measure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jwcarman.noir.exec.CommandResult;

/**
 * The files written by the measurement container into its output directory. The builder controls
 * them, so symlinks and oversized files are treated as missing.
 */
public record MeasureArtifacts(Path outDir) {
  static final long MAX_TEXT_BYTES = 1024 * 1024;
  static final long MAX_REPORT_BYTES = 64L * 1024 * 1024;
  private static final Pattern SOURCE = Pattern.compile("src/main/java/(.+)\\.java");

  public Optional<Integer> exit(String step) {
    var text = read(step + ".exit");
    try {
      return text.isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(text));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public String logTail(String step, int lines) {
    return CommandResult.tail(read(step + ".log"), lines);
  }

  /** A file's trimmed content, or empty if missing, a symlink, or over 1 MB. */
  public String read(String file) {
    var p = outDir.resolve(file);
    if (!usable(p, MAX_TEXT_BYTES)) {
      return "";
    }
    try {
      return Files.readString(p, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "";
    }
  }

  /** The path of a large report (jacoco/mutations XML) if usable, else empty. */
  public Optional<Path> report(String file) {
    var p = outDir.resolve(file);
    return usable(p, MAX_REPORT_BYTES) ? Optional.of(p) : Optional.empty();
  }

  public Path path(String relative) {
    return outDir.resolve(relative);
  }

  /** FQNs of changed main classes listed in {@code changed.txt}; other lines are ignored. */
  public List<String> changedClasses() {
    var classes = new ArrayList<String>();
    for (var line : read("changed.txt").split("\n")) {
      var m = SOURCE.matcher(line.strip());
      if (m.matches()) {
        classes.add(m.group(1).replace('/', '.'));
      }
    }
    return classes;
  }

  public Path exportClasses() {
    return outDir.resolve("export/classes");
  }

  public Path exportDeps() {
    return outDir.resolve("export/deps");
  }

  private static boolean usable(Path p, long max) {
    try {
      return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.size(p) <= max;
    } catch (IOException e) {
      return false;
    }
  }
}
