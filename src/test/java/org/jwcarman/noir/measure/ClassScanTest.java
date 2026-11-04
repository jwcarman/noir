package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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

  static List<Path> files(Path root) throws IOException {
    try (Stream<Path> s = Files.walk(root)) {
      return s.toList();
    }
  }
}
