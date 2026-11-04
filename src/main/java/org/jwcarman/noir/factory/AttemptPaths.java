package org.jwcarman.noir.factory;

import java.nio.file.Path;

/**
 * The layout of one attempt under the item directory. Only {@link #build()} is mounted into the
 * builder container; {@link #feedback()} sits beside it, out of the builder's reach, and holds the
 * feedback this attempt left for the next one.
 */
public record AttemptPaths(Path itemDir, int attempt) {
  public Path dir() {
    return itemDir.resolve("attempts").resolve(Integer.toString(attempt));
  }

  public Path build() {
    return dir().resolve("build");
  }

  public Path measure() {
    return dir().resolve("measure");
  }

  public Path grade() {
    return dir().resolve("grade");
  }

  public Path bundle() {
    return build().resolve("out.bundle");
  }

  public Path feedback() {
    return dir().resolve("FEEDBACK.md");
  }
}
