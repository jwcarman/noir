package org.jwcarman.noir.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Builds noir's container images from the Dockerfiles bundled on the classpath. */
public final class Images {
  static final String BUILDER_ROOT = "/docker/builder/";
  static final String GRADER_ROOT = "/docker/grader/";

  /**
   * Build-context files, relative to the image's resource root. An entry starting with {@code /} is
   * an absolute resource path placed at the context root under its file name, so one resource can
   * serve several images (the grader's ForbiddenScan is the single source of truth).
   */
  static final List<String> BUILDER_FILES =
      List.of(
          "Dockerfile",
          "toolchain/pom.xml",
          "toolchain/warm/pom.xml",
          "toolchain/warm/src/main/java/warm/Warm.java",
          "toolchain/warm/src/test/java/warm/WarmTest.java",
          "seal-check.sh",
          "build-prep.sh",
          "build-post.sh",
          "measure.sh",
          "ClassScan.java",
          GRADER_ROOT + "ForbiddenScan.java");

  static final List<String> GRADER_FILES =
      List.of("Dockerfile", "grade.sh", "grader.policy", "ForbiddenScan.java");

  private Images() {}

  /**
   * Builds the builder image under {@code tag} and returns its id. The build always runs; Docker's
   * layer cache makes it near-instant when nothing changed, and it guarantees the image matches the
   * Dockerfile shipped with this version of noir.
   */
  public static String ensureBuilder(ContainerPort docker, String tag) {
    return ensure(BUILDER_ROOT, BUILDER_FILES, docker, tag);
  }

  /** Builds the grader image under {@code tag} and returns its id (see {@link #ensureBuilder}). */
  public static String ensureGrader(ContainerPort docker, String tag) {
    return ensure(GRADER_ROOT, GRADER_FILES, docker, tag);
  }

  private static String ensure(String root, List<String> files, ContainerPort docker, String tag) {
    var context = extract(root, files);
    try {
      docker.build(context, tag);
    } finally {
      deleteRecursively(context);
    }
    return docker
        .imageId(tag)
        .orElseThrow(() -> new DockerException("Image " + tag + " missing after build"));
  }

  static Path extract(String root, List<String> files) {
    try {
      var dir = Files.createTempDirectory("noir-image");
      for (var file : files) {
        var shared = file.startsWith("/");
        var resource = shared ? file : root + file;
        try (var in = Images.class.getResourceAsStream(resource)) {
          if (in == null) {
            throw new IllegalStateException("Missing image resource " + resource);
          }
          var target = dir.resolve(shared ? file.substring(file.lastIndexOf('/') + 1) : file);
          Files.createDirectories(target.getParent());
          Files.copy(in, target);
        }
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path dir) {
    try (var paths = Files.walk(dir)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      // best effort
    }
  }
}
