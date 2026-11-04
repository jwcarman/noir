package org.jwcarman.noir.grade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jwcarman.noir.exec.CommandResult;

/** The files written by {@code grade.sh} into its output directory. */
public record GradeArtifacts(Path outDir) {

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

  /** A file's trimmed content, or an empty string if it does not exist. */
  public String read(String file) {
    var p = outDir.resolve(file);
    if (!Files.isRegularFile(p)) {
      return "";
    }
    try {
      return Files.readString(p).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Path path(String relative) {
    return outDir.resolve(relative);
  }
}
