package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.Specs;

class RepositoryApiTest {
  static final String COUNTER_PATH = "src/main/java/com/example/api/Counter.java";
  static final String COUNTER =
      """
      package com.example.api;

      import java.util.List;

      // a line comment that is not documentation
      /** A counter that starts at zero. */
      public class Counter {
        /** The largest count. */
        public static final int MAX = computeMax();

        private int count;

        /** Creates a counter at {@code start}. */
        public Counter(int start) {
          this.count = start;
        }

        private Counter() {
          this(0);
        }

        static {
          System.out.println("loaded");
        }

        /** Adds one to the count. */
        public void increment() {
          count += 1;
        }

        /** The counts so far. */
        protected List<Integer> history() {
          return List.of(count);
        }

        private static int computeMax() {
          return 10;
        }

        private static final class Secret {}

        int packageCount;

        Counter(String name) {
          this(0);
        }

        void packageMethod() {}

        protected static final class Guarded {}

        static final class Hidden {}

        /** A nested public type. */
        public interface Listener {
          /** Called on change. */
          void changed(int count);

          default void reset() {
            changed(0);
          }
        }
      }
      """;

  static Spec specNaming(String statement) {
    return Specs.valid()
        .withRequirements(
            List.of(new Requirement("R1", statement, List.of(new Example("x", "y", "z")))))
        .withConstraints(List.of());
  }

  @Test
  void stripsBodiesAndNonPublicMembers() {
    var api =
        RepositoryApi.render(
            Map.of(COUNTER_PATH, COUNTER), specNaming("nothing"), List.of(), 60_000);

    assertThat(api).startsWith("### " + COUNTER_PATH + "\n```java\n").contains("\n```\n");
    assertThat(api)
        .contains("package com.example.api;")
        .contains("import java.util.List;")
        .contains("/**\n * A counter that starts at zero.\n */")
        .contains("public class Counter {")
        .contains("public static final int MAX;")
        .contains("Creates a counter at {@code start}.")
        .contains("public Counter(int start) {")
        .contains("public void increment();")
        .contains("public interface Listener {")
        .contains("void changed(int count);")
        .contains("default void reset();");
    assertThat(api)
        .doesNotContain("count += 1")
        .doesNotContain("this.count = start")
        .doesNotContain("computeMax")
        .doesNotContain("private")
        .doesNotContain("Secret")
        .doesNotContain("history")
        .doesNotContain("protected")
        .doesNotContain("packageCount")
        .doesNotContain("Counter(String name)")
        .doesNotContain("packageMethod")
        .doesNotContain("Guarded")
        .doesNotContain("Hidden")
        .doesNotContain("loaded")
        .doesNotContain("a line comment")
        .doesNotContain("changed(0)")
        .doesNotContain("omitted");
  }

  /** {@code size} characters of Javadoc in a public class {@code name}. */
  static String typeOfSize(String name, int size) {
    return "package com.example.big;\n\n/** "
        + "x".repeat(size)
        + " */\npublic final class "
        + name
        + " {}\n";
  }

  @Test
  void capKeepsSpecNamedTypesWhole() {
    var sources =
        Map.of(
            "src/main/java/com/example/big/Alpha.java", typeOfSize("Alpha", 300),
            "src/main/java/com/example/big/Beta.java", typeOfSize("Beta", 300),
            "src/main/java/com/example/big/Gamma.java", typeOfSize("Gamma", 300),
            "src/main/java/com/example/big/Zeta.java", typeOfSize("Zeta", 300),
            "src/main/java/com/example/big/Omega.java", typeOfSize("Omega", 300));
    // Zeta is named by a requirement, Omega by the API contract; Alpha only as part of a word.
    var spec = specNaming("Zeta.of(n) returns an AlphaNumeric");
    var contract =
        List.of(
            new StubSource(
                "src/main/java/com/example/api/Foo.java",
                "package com.example.api;\npublic interface Foo { Omega omega(); }\n"));

    var api = RepositoryApi.render(sources, spec, contract, 1_500);

    assertThat(api.length()).isLessThanOrEqualTo(1_500);
    var omega = api.indexOf("### src/main/java/com/example/big/Omega.java");
    var zeta = api.indexOf("### src/main/java/com/example/big/Zeta.java");
    var alpha = api.indexOf("### src/main/java/com/example/big/Alpha.java");
    assertThat(omega).isZero();
    assertThat(zeta).isGreaterThan(omega);
    assertThat(alpha).isGreaterThan(zeta);
    assertThat(api).contains("public final class Omega {").contains("public final class Zeta {");
    assertThat(api).doesNotContain("Beta").doesNotContain("Gamma");
    // Each kept file is whole: its fence closes.
    assertThat(api.split("```java", -1)).hasSize(4);
    assertThat(api).endsWith("(… 2 more files omitted)\n");
  }

  @Test
  void unparseableFilesAreCountedAsOmitted() {
    var api =
        RepositoryApi.render(
            Map.of("src/main/java/com/example/Bad.java", "class {", COUNTER_PATH, COUNTER),
            specNaming("nothing"),
            List.of(),
            60_000);
    assertThat(api).contains("### " + COUNTER_PATH).doesNotContain("Bad.java");
    assertThat(api).endsWith("(… 1 more files omitted)\n");
  }

  /** Package-private top-level types are not API; a file left with no type is not shown. */
  @Test
  void packagePrivateTopLevelTypesAreLeftOut() {
    var sources =
        Map.of(
            "src/main/java/com/example/api/Internal.java",
            "package com.example.api;\n\nfinal class Internal {\n  public void run() {}\n}\n",
            "src/main/java/com/example/api/Mixed.java",
            "package com.example.api;\n\npublic class Mixed {}\n\nclass Helper {}\n");
    var api = RepositoryApi.render(sources, specNaming("nothing"), List.of(), 60_000);
    assertThat(api)
        .contains("public class Mixed {")
        .doesNotContain("Helper")
        .doesNotContain("Internal")
        .doesNotContain("omitted");
  }

  @Test
  void noSourcesRenderNothing() {
    assertThat(RepositoryApi.render(Map.of(), specNaming("nothing"), List.of(), 60_000)).isEmpty();
  }
}
