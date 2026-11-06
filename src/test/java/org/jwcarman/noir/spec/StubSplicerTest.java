package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubSplicerTest {
  static final String PATH = "src/main/java/com/example/Csv.java";

  static final String BASE =
      """
      package com.example;

      import java.util.List;

      // A file-level comment.
      public final class Csv {

        private static final char COMMA = ',';

        /** Old docs. */
        public static List<List<String>> parse(String text) {
          // keep me
          return List.of(List.of(text));
        }

        // A trailing comment about helpers.
        private static int helper() {
          return 1;
        }
      }
      """;

  static final String UOE = "    throw new UnsupportedOperationException();\n";

  static String fragment(String imports, String header, String body) {
    return "package com.example;\n\n" + imports + "\n" + header + " {\n" + body + "}\n";
  }

  static String csvFragment(String body) {
    return fragment("import java.util.List;\n", "public final class Csv", body);
  }

  // ---- Adding and changing members ----

  @Test
  void addsNewMethodAtEndOfType() {
    var frag =
        csvFragment(
            """
              /** New overload. */
              public static List<List<String>> parse(String text, char separator) {
                throw new UnsupportedOperationException();
              }
            """);

    var result = StubSplicer.splice(PATH, BASE, frag);

    var expected =
        BASE.replace(
            "    return 1;\n  }\n}\n",
            """
                return 1;
              }

              /** New overload. */
              public static List<List<String>> parse(String text, char separator) {
                throw new UnsupportedOperationException();
              }
            }
            """);
    assertThat(result.content()).isEqualTo(expected);
    assertThat(result.added()).containsExactly("parse(String, char)");
    assertThat(result.changed()).isEmpty();
  }

  @Test
  void changedMemberKeepsBaseBodyAndTakesFragmentJavadoc() {
    var frag =
        csvFragment(
            """
              /**
               * Changed: a lone CR is now a line break.
               *
               * @param text the input
               */
              public static List<List<String>> parse(String text) {
                throw new UnsupportedOperationException();
              }
            """);

    var result = StubSplicer.splice(PATH, BASE, frag);

    var expected =
        BASE.replace(
            "  /** Old docs. */\n",
            """
              /**
               * Changed: a lone CR is now a line break.
               *
               * @param text the input
               */
            """);
    assertThat(result.content()).isEqualTo(expected);
    assertThat(result.content()).contains("    // keep me\n    return List.of(List.of(text));\n");
    assertThat(result.content()).doesNotContain("Old docs");
    assertThat(result.changed()).containsExactly("parse(String)");
    assertThat(result.added()).isEmpty();
  }

  @Test
  void changedMemberWithoutBaseJavadocGainsOne() {
    var frag =
        csvFragment(
            """
              /** Helper now documented. */
              private static int helper() {
                throw new UnsupportedOperationException();
              }
            """);

    var result = StubSplicer.splice(PATH, BASE, frag);

    var expected =
        BASE.replace(
            "  private static int helper() {\n",
            "  /** Helper now documented. */\n  private static int helper() {\n");
    assertThat(result.content()).isEqualTo(expected);
    assertThat(result.changed()).containsExactly("helper()");
  }

  @Test
  void changedJavadocIsReindentedToTheBaseIndentation() {
    var base = "package com.example;\n\npublic class A {\n    /** Old. */\n    void a() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /**\n   * New.\n   */\n"
            + "  void a() {\n    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic class A {\n    /**\n     * New.\n     */\n"
                + "    void a() {}\n}\n");
  }

  // ---- Imports and preservation ----

  @Test
  void mergesImportsWithoutDuplicates() {
    var frag =
        fragment(
            "import java.util.List;\nimport java.util.Optional;\nimport static java.util.Objects.requireNonNull;\n",
            "public final class Csv",
            """
              public static Optional<String> first(List<String> items) {
                throw new UnsupportedOperationException();
              }
            """);

    var result = StubSplicer.splice(PATH, BASE, frag);

    assertThat(result.content())
        .startsWith(
            """
            package com.example;

            import java.util.List;
            import java.util.Optional;
            import static java.util.Objects.requireNonNull;

            // A file-level comment.
            """);
    assertThat(result.content().split("import java.util.List;", -1)).hasSize(2);
    assertThat(result.added()).containsExactly("first(List<String>)");
  }

  @Test
  void addsImportsAfterPackageWhenBaseHasNone() {
    var base = "package com.example;\n\npublic class A {\n  void a() {}\n}\n";
    var frag =
        "package com.example;\n\nimport java.util.List;\n\npublic class A {\n"
            + "  List<String> b() {\n    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\nimport java.util.List;\n\npublic class A {\n  void a() {}\n\n"
                + "  List<String> b() {\n    throw new UnsupportedOperationException();\n  }\n}\n");
  }

  @Test
  void preservesCommentsOrderAndOtherMembers() {
    var frag =
        fragment(
            "import java.util.List;\nimport java.util.Optional;\n",
            "public final class Csv",
            """
              /** Changed docs. */
              public static List<List<String>> parse(String text) {
                throw new UnsupportedOperationException();
              }

              /** Added. */
              public static Optional<String> first(String text) {
                throw new UnsupportedOperationException();
              }
            """);

    var result = StubSplicer.splice(PATH, BASE, frag);

    var baseLines = new ArrayList<>(List.of(BASE.split("\n", -1)));
    baseLines.remove("  /** Old docs. */");
    var outLines = List.of(result.content().split("\n", -1));
    var index = 0;
    for (var line : outLines) {
      if (index < baseLines.size() && line.equals(baseLines.get(index))) {
        index++;
      }
    }
    assertThat(index).as("every base line appears in order").isEqualTo(baseLines.size());
    assertThat(result.content())
        .contains("  /** Changed docs. */\n  public static List<List<String>> parse(String text) {")
        .contains("// A file-level comment.")
        .contains("// A trailing comment about helpers.")
        .contains("private static final char COMMA = ',';");
    assertThat(result.content().indexOf("first(String text)"))
        .isGreaterThan(result.content().indexOf("helper()"));
  }

  @Test
  void splicePreservesCrlf() {
    var base = BASE.replace("\n", "\r\n");
    var frag =
        fragment(
            "import java.util.List;\nimport java.util.Optional;\n",
            "public final class Csv",
            """
              /**
               * Changed.
               */
              public static List<List<String>> parse(String text) {
                throw new UnsupportedOperationException();
              }

              /**
               * Added.
               */
              public static Optional<String> first(String text) {
                throw new UnsupportedOperationException();
              }
            """);

    var result = StubSplicer.splice(PATH, base, frag);

    assertThat(result.content().replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    assertThat(result.content())
        .contains("import java.util.Optional;\r\n")
        .contains("  /**\r\n   * Changed.\r\n   */\r\n")
        .contains(
            "  }\r\n\r\n  /**\r\n   * Added.\r\n   */\r\n"
                + "  public static Optional<String> first(String text) {\r\n"
                + "    throw new UnsupportedOperationException();\r\n  }\r\n}\r\n");
    assertThat(result.added()).containsExactly("first(String)");
    assertThat(result.changed()).containsExactly("parse(String)");
  }

  // ---- Annotations, signatures and other types ----

  @Test
  void changedMemberKeepsBaseAnnotations() {
    var base =
        "package com.example;\n\npublic class A {\n  /** Old. */\n  @Override\n  @Deprecated\n"
            + "  public String toString() {\n    return \"a\";\n  }\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  @SuppressWarnings(\"x\")\n"
            + "  public String toString() {\n    throw new UnsupportedOperationException();\n"
            + "  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content()).isEqualTo(base.replace("/** Old. */", "/** New. */"));
    assertThat(result.changed()).containsExactly("toString()");
  }

  @Test
  void addedMemberTakesFragmentAnnotations() {
    var base = "package com.example;\n\npublic class A {\n  void a() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  @Deprecated\n"
            + "  public int b() {\n    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic class A {\n  void a() {}\n\n  /** New. */\n"
                + "  @Deprecated\n  public int b() {\n"
                + "    throw new UnsupportedOperationException();\n  }\n}\n");
    assertThat(result.added()).containsExactly("b()");
  }

  @Test
  void genericAndVarargsSignaturesMatch() {
    var base =
        """
        package com.example;

        import java.util.List;

        public class A {
          /** a */
          public <T> T get(Class<T> type) {
            return null;
          }

          /** b */
          public void log(String... parts) {}

          /** c */
          public void take(List<String> items) {}
        }
        """;
    var frag =
        """
        package com.example;

        import java.util.List;

        public class A {
          /** A2 */
          public <T> T get(Class< T > type) {
            throw new UnsupportedOperationException();
          }

          /** B2 */
          public void log(String... parts) {
            throw new UnsupportedOperationException();
          }

          /** C2 */
          public void take(List<Integer> items) {
            throw new UnsupportedOperationException();
          }

          /** D2 */
          public void take(List<String> items) {
            throw new UnsupportedOperationException();
          }
        }
        """;

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.changed())
        .containsExactly("get(Class<T>)", "log(String...)", "take(List<String>)");
    assertThat(result.added()).containsExactly("take(List<Integer>)");
    assertThat(result.content())
        .contains("/** A2 */\n  public <T> T get(Class<T> type) {\n    return null;")
        .contains("/** B2 */\n  public void log(String... parts) {}")
        .contains("/** D2 */\n  public void take(List<String> items) {}")
        .contains("/** C2 */\n  public void take(List<Integer> items) {\n");
  }

  @Test
  void secondTopLevelTypeIsUntouched() {
    var base =
        """
        package com.example;

        public class A {
          void a() {}
        }

        class Helper {
          void a() {}
        }
        """;
    var frag =
        """
        package com.example;

        class Helper {
          /** New. */
          void a() {
            throw new UnsupportedOperationException();
          }

          void b() {
            throw new UnsupportedOperationException();
          }
        }
        """;

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            """
            package com.example;

            public class A {
              void a() {}
            }

            class Helper {
              /** New. */
              void a() {}

              void b() {
                throw new UnsupportedOperationException();
              }
            }
            """);
    assertThat(result.changed()).containsExactly("a()");
    assertThat(result.added()).containsExactly("b()");
  }

  @Test
  void addsToAnEmptyTypeWithTwoSpaceIndent() {
    var base = "package com.example;\n\npublic class A {\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n    void b() {\n"
            + "      throw new UnsupportedOperationException();\n    }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic class A {\n\n  void b() {\n"
                + "    throw new UnsupportedOperationException();\n  }\n}\n");
  }

  @Test
  void addsConstructor() {
    var base = "package com.example;\n\npublic class A {\n  A() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  A() {\n"
            + "    throw new UnsupportedOperationException();\n  }\n\n  A(int x) {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.changed()).containsExactly("A()");
    assertThat(result.added()).containsExactly("A(int)");
    assertThat(result.content()).contains("  /** New. */\n  A() {}\n");
  }

  // ---- Rejections ----

  static void assertRejected(String base, String frag, String... fragments) {
    assertThatThrownBy(() -> StubSplicer.splice(PATH, base, frag))
        .isInstanceOf(SpliceException.class)
        .satisfies(e -> {
          var message = e.getMessage();
          assertThat(message).startsWith(PATH + ": ").doesNotContain("\n");
          for (var f : fragments) {
            assertThat(message).contains(f);
          }
        });
  }

  static String method(String signature) {
    return "  " + signature + " {\n" + UOE + "  }\n";
  }

  @Test
  void rejectsMissingTargetType() {
    assertRejected(
        BASE, fragment("", "public final class Tsv", method("void a()")), "Tsv", "top-level type");
  }

  @Test
  void rejectsKindMismatch() {
    assertRejected(
        BASE, fragment("", "public interface Csv", method("void a()")), "Csv", "interface");
  }

  @Test
  void rejectsTypeModifierMismatch() {
    assertRejected(BASE, fragment("", "public class Csv", method("void a()")), "modifiers");
  }

  @Test
  void rejectsChangedMemberWithDifferentReturnType() {
    assertRejected(
        BASE,
        csvFragment(method("public static List<String> parse(String text)")),
        "parse(String)",
        "return type");
  }

  @Test
  void rejectsChangedMemberWithDifferentModifiers() {
    assertRejected(
        BASE,
        csvFragment(method("public List<List<String>> parse(String text)")),
        "parse(String)",
        "modifiers");
  }

  @Test
  void rejectsChangedMemberWithDifferentTypeParameters() {
    assertRejected(
        BASE,
        csvFragment(method("public static <T> List<List<String>> parse(String text)")),
        "parse(String)",
        "type parameters");
  }

  @Test
  void rejectsChangedMemberWithDifferentThrows() {
    assertRejected(
        BASE,
        csvFragment(method("public static List<List<String>> parse(String text) throws Exception")),
        "parse(String)",
        "throws");
  }

  @Test
  void rejectsBodyThatIsNotTheUnsupportedOperationThrow() {
    assertRejected(
        BASE,
        csvFragment("  public static int count(String text) {\n    return 0;\n  }\n"),
        "count(String)",
        "throw new UnsupportedOperationException();");
  }

  @Test
  void rejectsBodyThrowingWithAMessage() {
    assertRejected(
        BASE,
        csvFragment(
            "  public static int count(String text) {\n"
                + "    throw new UnsupportedOperationException(\"x\");\n  }\n"),
        "count(String)");
  }

  @Test
  void rejectsField() {
    assertRejected(BASE, csvFragment("  private int x;\n"), "field");
  }

  @Test
  void rejectsInitializer() {
    assertRejected(BASE, csvFragment("  static {}\n"), "initializer");
  }

  @Test
  void rejectsNestedType() {
    assertRejected(BASE, csvFragment("  class Inner {}\n"), "nested type");
  }

  @Test
  void rejectsDuplicateSignatures() {
    assertRejected(
        BASE,
        csvFragment(
            method("public static int count(String a)") + method("static int count(String b)")),
        "count(String)",
        "twice");
  }

  @Test
  void rejectsUnparseableFragment() {
    assertRejected(
        BASE, "package com.example;\n\npublic final class Csv {\n  void (\n}\n", "fragment");
  }

  @Test
  void rejectsUnparseableBase() {
    assertRejected("public class {", csvFragment(method("void a()")), "base commit");
  }

  @Test
  void rejectsPackageMismatch() {
    assertRejected(
        BASE,
        "package com.other;\n\npublic final class Csv {\n" + method("void a()") + "}\n",
        "com.other",
        "com.example");
  }

  @Test
  void rejectsMoreThanOneFragmentType() {
    assertRejected(
        BASE, csvFragment(method("void a()")) + "class Other {}\n", "exactly one top-level type");
  }
}
