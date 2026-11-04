package org.jwcarman.noir.grade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.feedback.TestOutcome;
import org.jwcarman.noir.reports.JUnitXmlReports;

/**
 * The files written by {@code grade.sh} into its output directory. Everything in there may have
 * been touched by builder code, so symlinks and oversized files are treated as missing.
 */
public record GradeArtifacts(Path outDir) {
  static final long MAX_TEXT_BYTES = 1024 * 1024;
  static final long MAX_REPORT_BYTES = 64L * 1024 * 1024;

  /** The exit code a step recorded, or empty if the step never finished. */
  public Optional<Integer> exit(String step) {
    var text = read(step + ".exit");
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(text));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public String logTail(String step, int lines) {
    return CommandResult.tail(read(step + ".log"), lines);
  }

  /** A file's trimmed content, or an empty string if it is missing, a symlink, or too large. */
  public String read(String file) {
    var p = outDir.resolve(file);
    if (!regular(p, MAX_TEXT_BYTES)) {
      return "";
    }
    try {
      return Files.readString(p, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "";
    }
  }

  public Path path(String relative) {
    return outDir.resolve(relative);
  }

  /**
   * Test outcomes from the JUnit XML reports in {@code dir}. Symlinks, oversized files, and reports
   * that do not parse are ignored.
   */
  public List<TestOutcome> outcomes(String dir) {
    var reports = path(dir);
    if (!Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<Path> files;
    try (var s = Files.list(reports)) {
      files = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var outcomes = new ArrayList<TestOutcome>();
    for (var file : files) {
      if (!regular(file, MAX_REPORT_BYTES)) {
        continue;
      }
      Path scratch = null;
      try {
        scratch = Files.createTempDirectory("noir-report");
        Files.copy(file, scratch.resolve("report.xml"));
        outcomes.addAll(JUnitXmlReports.parse(scratch));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (RuntimeException e) {
        // an unparseable report contributes no outcomes; missing tests are judged as failed
      } finally {
        deleteQuietly(scratch);
      }
    }
    return outcomes;
  }

  private static boolean regular(Path p, long maxBytes) {
    try {
      return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.size(p) <= maxBytes;
    } catch (IOException e) {
      return false;
    }
  }

  private static void deleteQuietly(Path dir) {
    if (dir == null) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      // best effort
    }
  }
}
