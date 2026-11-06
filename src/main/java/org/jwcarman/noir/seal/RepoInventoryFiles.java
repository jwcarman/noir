package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What {@code seal-check.sh inventory} lists: {@code libraries.txt} (the repository's runtime and
 * test-scope dependencies as {@code group:artifact:version}, JUnit's own left out) and {@code
 * packages.txt} (the package of every class in those dependencies' jars).
 */
public record RepoInventoryFiles(List<String> libraries, List<String> packages) {
  public static final String LIBRARIES = "libraries.txt";
  public static final String PACKAGES = "packages.txt";

  public RepoInventoryFiles {
    libraries = List.copyOf(libraries);
    packages = List.copyOf(packages);
  }

  /** Reads both files from {@code dir}, one entry per non-blank line. */
  public static RepoInventoryFiles read(Path dir) {
    return new RepoInventoryFiles(lines(dir.resolve(LIBRARIES)), lines(dir.resolve(PACKAGES)));
  }

  private static List<String> lines(Path file) {
    try {
      return Files.readAllLines(file).stream()
          .map(String::strip)
          .filter(s -> !s.isEmpty())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
