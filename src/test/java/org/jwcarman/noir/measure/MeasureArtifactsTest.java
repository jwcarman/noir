package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasureArtifactsTest {
  @TempDir Path out;

  @Test
  void changedClassesMapSourcesAndIgnoreOthers() throws IOException {
    Files.writeString(
        out.resolve("changed.txt"),
        "src/main/java/a/b/C.java\nsrc/test/java/a/b/CTest.java\nREADME.md\nsrc/main/java/D.java\n");
    var a = new MeasureArtifacts(out);
    assertThat(a.changedClasses()).containsExactly("a.b.C", "D");
    assertThat(a.exportClasses()).isEqualTo(out.resolve("export/classes"));
    assertThat(a.exportDeps()).isEqualTo(out.resolve("export/deps"));
    assertThat(a.path("x")).isEqualTo(out.resolve("x"));
  }

  @Test
  void exitAndLogTail() throws IOException {
    var a = new MeasureArtifacts(out);
    assertThat(a.exit("build")).isEmpty();
    Files.writeString(out.resolve("build.exit"), "x");
    assertThat(a.exit("build")).isEmpty();
    Files.writeString(out.resolve("build.exit"), "2\n");
    assertThat(a.exit("build")).isEqualTo(Optional.of(2));
    Files.writeString(out.resolve("build.log"), "1\n2\n3\n");
    assertThat(a.logTail("build", 2)).isEqualTo("2\n3");
  }

  @Test
  void symlinksAndOversizedFilesAreMissing() throws IOException {
    var target = Files.writeString(out.resolve("real.txt"), "hi");
    Files.createSymbolicLink(out.resolve("link.txt"), target);
    Files.write(out.resolve("big.txt"), new byte[(int) MeasureArtifacts.MAX_TEXT_BYTES + 1]);
    var a = new MeasureArtifacts(out);
    assertThat(a.read("real.txt")).isEqualTo("hi");
    assertThat(a.read("link.txt")).isEmpty();
    assertThat(a.read("big.txt")).isEmpty();
    assertThat(a.report("big.txt")).isPresent();
    assertThat(a.report("link.txt")).isEmpty();
    assertThat(a.report("nope.xml")).isEmpty();
  }
}
