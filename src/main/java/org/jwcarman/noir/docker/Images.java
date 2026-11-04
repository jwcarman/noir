package org.jwcarman.noir.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Builds noir's container images from the Dockerfiles bundled on the classpath. */
public final class Images {
  static final String BUILDER_ROOT = "/docker/builder/";
  static final List<String> BUILDER_FILES =
      List.of("Dockerfile", "toolchain/pom.xml", "seal-check.sh");

  private Images() {}

  /**
   * Builds the builder image under {@code tag} and returns its id. The build always runs; Docker's
   * layer cache makes it near-instant when nothing changed, and it guarantees the image matches the
   * Dockerfile shipped with this version of noir.
   */
  public static String ensureBuilder(ContainerPort docker, String tag) {
    var context = extract(BUILDER_ROOT, BUILDER_FILES);
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
        try (var in = Images.class.getResourceAsStream(root + file)) {
          if (in == null) {
            throw new IllegalStateException("Missing image resource " + root + file);
          }
          var target = dir.resolve(file);
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
