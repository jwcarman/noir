package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiConformanceTest {
  private static final Path DIR = Path.of("src/test/resources/fixtures/measure");

  private static String stub() throws IOException {
    return Files.readString(DIR.resolve("javap-stub.txt"));
  }

  @Test
  void extraBuilderMembersAreAllowed() throws IOException {
    var built = Files.readString(DIR.resolve("javap-built.txt"));
    assertThat(ApiConformance.mismatches(stub(), built)).isEmpty();
  }

  @Test
  void missingMethodReported() throws IOException {
    var built = stub().replace("  public int value();\n", "");
    assertThat(ApiConformance.mismatches(stub(), built))
        .containsExactly("missing: public int value();");
  }

  @Test
  void changedReturnTypeReported() throws IOException {
    var built = stub().replace("public int value();", "public long value();");
    assertThat(ApiConformance.mismatches(stub(), built))
        .containsExactly("missing: public int value();");
  }

  @Test
  void whitespaceIsNormalizedAndClassLineChecked() throws IOException {
    var built = stub().replace("public class", "public   final class");
    assertThat(ApiConformance.mismatches(stub(), built))
        .containsExactly("missing: public class com.example.api.Counter {");
    assertThat(
            ApiConformance.mismatches(stub(), stub().replace("  public void", "\t public  void")))
        .isEmpty();
  }

  @Test
  void membersAreComparedPerClass() {
    var stub =
        "public class p.A {\n  public void reset();\n}\npublic class p.B {\n  public void reset();\n}\n";
    var built = "public class p.A {\n}\npublic class p.B {\n  public void reset();\n}\n";
    assertThat(ApiConformance.mismatches(stub, built))
        .containsExactly("missing: public void reset();");
  }

  @Test
  void missingClassBlockReportsDeclaration() {
    var stub =
        "public class p.A {\n  public void x();\n}\npublic interface p.B<T> extends p.C {\n  public abstract T get();\n}\n";
    var built = "public class p.A {\n  public void x();\n}\n";
    assertThat(ApiConformance.mismatches(stub, built))
        .containsExactly("missing: public interface p.B<T> extends p.C {");
  }
}
