package org.jwcarman.noir.measure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
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

  /**
   * A file's trimmed content; an empty file gives an empty string, but a missing, symlinked,
   * oversized or non-UTF-8 file is an incomplete measurement.
   */
  public String require(String file) {
    var p = outDir.resolve(file);
    if (!usable(p, MAX_TEXT_BYTES)) {
      throw incomplete(file);
    }
    try {
      var bytes = Files.readAllBytes(p);
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString()
          .strip();
    } catch (IOException e) {
      throw incomplete(file);
    }
  }

  /** The exit code a step recorded; a missing or malformed {@code .exit} is incomplete. */
  public int requireExit(String step) {
    try {
      return Integer.parseInt(require(step + ".exit"));
    } catch (NumberFormatException e) {
      throw incomplete(step + ".exit");
    }
  }

  /** The path of a large report, or an incomplete measurement if it is not usable. */
  public Path requireReport(String file) {
    return report(file).orElseThrow(() -> incomplete(file));
  }

  private static IllegalStateException incomplete(String file) {
    return new IllegalStateException("measurement incomplete: " + file);
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
    for (var line : require("changed.txt").split("\n")) {
      var m = SOURCE.matcher(line.strip());
      if (m.matches()) {
        var fqn = m.group(1).replace('/', '.');
        var simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        if (!simple.equals("package-info") && !simple.equals("module-info")) {
          classes.add(fqn);
        }
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
