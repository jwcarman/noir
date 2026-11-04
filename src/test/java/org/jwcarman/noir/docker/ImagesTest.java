package org.jwcarman.noir.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImagesTest {
  @Test
  void builderContextSharesTheGradersForbiddenScan() throws Exception {
    var dir = Images.extract(Images.BUILDER_ROOT, Images.BUILDER_FILES);
    try {
      var grader =
          Path.of(Images.class.getResource(Images.GRADER_ROOT + "ForbiddenScan.java").toURI());
      assertThat(dir.resolve("ForbiddenScan.java")).hasSameBinaryContentAs(grader);
      for (var f :
          new String[] {
            "Dockerfile", "measure.sh", "build-prep.sh", "build-post.sh", "ClassScan.java"
          }) {
        assertThat(dir.resolve(f)).isRegularFile();
      }
      assertThat(dir.resolve("toolchain/pom.xml")).isRegularFile();
    } finally {
      try (var paths = Files.walk(dir)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }
}
