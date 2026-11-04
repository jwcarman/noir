package org.jwcarman.noir.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Specs;

public class HoldoutCatalogTest {
  @TempDir Path tmp;

  /** Writes two test classes; {@code both} is tagged R1 and R2. */
  public static void writeFixture(Path holdoutDir) throws IOException {
    var pkg = ItemId.holdoutPackage(Specs.ID);
    var dir = holdoutDir.resolve(pkg.replace('.', '/'));
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("CacheTest.java"),
        """
        package %s;

        import org.junit.jupiter.api.Tag;
        import org.junit.jupiter.api.Test;

        class CacheTest {
          /** Gets what was put. */
          @Test
          @Tag("R1")
          void getsPut() {}

          @Test
          @Tag("R1")
          @Tag("R2")
          void both() {}
        }
        """
            .formatted(pkg));
    Files.writeString(
        dir.resolve("BlankKeyTest.java"),
        """
        package %s;

        import org.junit.jupiter.api.Tag;
        import org.junit.jupiter.api.Test;

        class BlankKeyTest {
          @Test
          @Tag("R2")
          void rejectsBlank() {}
        }
        """
            .formatted(pkg));
  }

  @Test
  void catalogListsEveryTestWithItsTagsAndSource() throws IOException {
    writeFixture(tmp);

    var tests = HoldoutCatalog.load(Specs.valid(), tmp);

    assertThat(tests)
        .extracting(HoldoutCatalog.HoldoutTest::id)
        .containsExactlyInAnyOrder(
            "CacheTest#getsPut", "CacheTest#both", "BlankKeyTest#rejectsBlank");
    var both =
        tests.stream().filter(t -> t.id().equals("CacheTest#both")).findFirst().orElseThrow();
    assertThat(both.requirements()).containsExactlyInAnyOrder("R1", "R2");
    assertThat(both.source()).contains("void both()").contains("@Tag(\"R2\")");
    var gets =
        tests.stream().filter(t -> t.id().equals("CacheTest#getsPut")).findFirst().orElseThrow();
    assertThat(gets.source()).contains("Gets what was put.").contains("void getsPut()");
  }

  @Test
  void unparseableHoldoutFileIsAnError() throws IOException {
    writeFixture(tmp);
    var dir = tmp.resolve(ItemId.holdoutPackage(Specs.ID).replace('.', '/'));
    Files.writeString(dir.resolve("BrokenTest.java"), "class BrokenTest { void x( {");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> HoldoutCatalog.load(Specs.valid(), tmp))
        .isInstanceOf(HoldoutCatalog.UnreadableHoldoutException.class)
        .hasMessageContaining("BrokenTest.java");
  }
}
