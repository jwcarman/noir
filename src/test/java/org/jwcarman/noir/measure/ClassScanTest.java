package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.ProcessCommandRunner;

/** Runs the real {@code ClassScan.java} shipped in the builder image, on the host JDK. */
class ClassScanTest {
  @TempDir Path tmp;
  Path src;
  Path classes;

  @BeforeEach
  void dirs() throws IOException {
    src = Files.createDirectories(tmp.resolve("src"));
    classes = Files.createDirectories(tmp.resolve("classes"));
  }

  void compile(Map<String, String> sources) throws IOException {
    var argv = new ArrayList<>(List.of("javac", "-d", classes.toString()));
    for (var e : sources.entrySet()) {
      var p = src.resolve(e.getKey());
      Files.createDirectories(p.getParent());
      Files.writeString(p, e.getValue());
      argv.add(p.toString());
    }
    var r = new ProcessCommandRunner().run(Command.of(argv));
    assertThat(r.ok()).as(r.tail(20)).isTrue();
  }

  record Scan(List<String> generated, List<String> targets) {}

  Scan scan(String... changed) throws Exception {
    var script = Path.of(getClass().getResource("/docker/builder/ClassScan.java").toURI());
    var changedTxt = tmp.resolve("changed.txt");
    Files.write(changedTxt, List.of(changed));
    var generated = tmp.resolve("generated.txt");
    var targets = tmp.resolve("targets.txt");
    var r =
        new ProcessCommandRunner()
            .run(
                Command.of(
                    "java",
                    script.toString(),
                    classes.toString(),
                    changedTxt.toString(),
                    generated.toString(),
                    targets.toString()));
    assertThat(r.ok()).as(r.tail(20)).isTrue();
    return new Scan(Files.readAllLines(generated), Files.readAllLines(targets));
  }

  static final String GENERATED_ANNOTATION =
      """
      package com.example.gen;

      public @interface Generated {}
      """;

  @Test
  void targetsEveryClassCompiledFromAChangedSource() throws Exception {
    compile(
        Map.of(
            "com/example/api/Counter.java",
            """
            package com.example.api;

            public class Counter {
              static class Inner {}

              Runnable r = () -> {};
            }

            class Helper {}
            """,
            "com/example/api/Other.java",
            "package com.example.api;\n\npublic class Other {}\n"));
    var s = scan("src/main/java/com/example/api/Counter.java", "README.md");
    assertThat(s.targets())
        .containsExactlyInAnyOrder(
            "com.example.api.Counter", "com.example.api.Counter$Inner", "com.example.api.Helper");
    assertThat(s.generated()).isEmpty();
  }

  @Test
  void flagsClassAndMethodLevelGeneratedAnnotations() throws Exception {
    compile(
        Map.of(
            "com/example/gen/Generated.java",
            GENERATED_ANNOTATION,
            "com/example/api/A.java",
            "package com.example.api;\n\n@com.example.gen.Generated\npublic class A {}\n",
            "com/example/api/B.java",
            """
            package com.example.api;

            public class B {
              @com.example.gen.Generated
              @Deprecated(since = "1", forRemoval = true)
              public int[] m() {
                return new int[] {1};
              }
            }
            """,
            "com/example/api/C.java",
            "package com.example.api;\n\n@com.example.gen.Generated\npublic class C {}\n"));
    var s = scan("src/main/java/com/example/api/A.java", "src/main/java/com/example/api/B.java");
    assertThat(s.generated()).containsExactlyInAnyOrder("com.example.api.A", "com.example.api.B");
    assertThat(s.targets()).containsExactlyInAnyOrder("com.example.api.A", "com.example.api.B");
  }

  @Test
  void unreadableClassFileInAChangedPackageFailsClosed() throws Exception {
    compile(Map.of("com/example/api/A.java", "package com.example.api;\n\npublic class A {}\n"));
    Files.writeString(classes.resolve("com/example/api/Junk.class"), "not a class");
    var s = scan("src/main/java/com/example/api/A.java");
    assertThat(s.generated()).containsExactly("unreadable class file: com/example/api/Junk.class");
  }

  @Test
  void defaultPackageAndNoChanges() throws Exception {
    compile(Map.of("Top.java", "public class Top {}\n"));
    assertThat(scan("src/main/java/Top.java").targets()).containsExactly("Top");
    assertThat(scan().targets()).isEmpty();
  }

  /** A minimal class file for com.example.api.A from A.java with one class-level attribute. */
  static byte[] craftedClass(String attrName, byte[] attrBody, int declaredLength)
      throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(bytes);
    out.writeInt(0xCAFEBABE);
    out.writeShort(0);
    out.writeShort(65);
    String[] utf8 = {"com/example/api/A", "java/lang/Object", "SourceFile", "A.java", attrName};
    out.writeShort(1 + utf8.length + 2); // utf8 entries #1..#5, classes #6, #7
    for (var u : utf8) {
      out.writeByte(1);
      out.writeUTF(u);
    }
    out.writeByte(7);
    out.writeShort(1);
    out.writeByte(7);
    out.writeShort(2);
    out.writeShort(0x21);
    out.writeShort(6);
    out.writeShort(7);
    out.writeShort(0); // interfaces
    out.writeShort(0); // fields
    out.writeShort(0); // methods
    out.writeShort(2);
    out.writeShort(3);
    out.writeInt(2);
    out.writeShort(4);
    out.writeShort(5);
    out.writeInt(declaredLength);
    out.write(attrBody);
    out.flush();
    return bytes.toByteArray();
  }

  void plant(byte[] classFile) throws IOException {
    var dir = Files.createDirectories(classes.resolve("com/example/api"));
    Files.write(dir.resolve("A.class"), classFile);
  }

  @Test
  void deeplyNestedAnnotationValuesFailClosed() throws Exception {
    int depth = 200_000;
    var body = new java.io.ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(body);
    out.writeShort(1); // one annotation
    out.writeShort(1); // type: com/example/api/A (any utf8)
    out.writeShort(1); // one element pair
    out.writeShort(1);
    for (int i = 0; i < depth; i++) {
      out.writeByte('[');
      out.writeShort(1);
    }
    out.writeByte('I');
    out.writeShort(1);
    out.flush();
    plant(craftedClass("RuntimeVisibleAnnotations", body.toByteArray(), body.size()));
    var s = scan("src/main/java/com/example/api/A.java");
    assertThat(s.generated()).containsExactly("unreadable class file: com/example/api/A.class");
  }

  @Test
  void hugeAttributeLengthFailsClosed() throws Exception {
    plant(craftedClass("Junk", new byte[] {1, 2, 3}, Integer.MAX_VALUE));
    var s = scan("src/main/java/com/example/api/A.java");
    assertThat(s.generated()).containsExactly("unreadable class file: com/example/api/A.class");
  }

  @Test
  void craftedClassWithinBoundsIsRead() throws Exception {
    plant(craftedClass("Junk", new byte[] {1, 2, 3}, 3));
    var s = scan("src/main/java/com/example/api/A.java");
    assertThat(s.generated()).isEmpty();
    assertThat(s.targets()).containsExactly("com.example.api.A");
  }
}
