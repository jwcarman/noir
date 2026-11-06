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
          public void take(List<String> items) {
            throw new UnsupportedOperationException();
          }
        }
        """;

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.changed())
        .containsExactly("get(Class<T>)", "log(String...)", "take(List<String>)");
    assertThat(result.added()).isEmpty();
    assertThat(result.content())
        .contains("/** A2 */\n  public <T> T get(Class<T> type) {\n    return null;")
        .contains("/** B2 */\n  public void log(String... parts) {}")
        .contains("/** C2 */\n  public void take(List<String> items) {}");
  }

  @Test
  void qualifiedAndSimpleParameterTypesAreTheSameSignature() {
    var base =
        """
        package com.example;

        public class A {
          /** a */
          public void f(java.lang.String s) {}

          /** b */
          public void g(java.util.Map<java.lang.String, java.util.List<java.io.File>>[] m) {}

          /** c */
          public void h(java.lang.Object... xs) {}

          /** d */
          public void e(java.util.Map.Entry<String, String> entry) {}
        }
        """;
    var frag =
        """
        package com.example;

        import java.io.File;
        import java.util.List;
        import java.util.Map;

        public class A {
          /** A2 */
          public void f(String s) {
            throw new UnsupportedOperationException();
          }

          /** B2 */
          public void g(Map<String, List<File>>[] m) {
            throw new UnsupportedOperationException();
          }

          /** C2 */
          public void h(Object... xs) {
            throw new UnsupportedOperationException();
          }

          /** D2 */
          public void e(Map.Entry<java.lang.String, String> entry) {
            throw new UnsupportedOperationException();
          }
        }
        """;

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.added()).isEmpty();
    assertThat(result.changed())
        .containsExactly(
            "f(String)",
            "g(Map<String, List<File>>[])",
            "h(Object...)",
            "e(Map.Entry<java.lang.String, String>)");
    assertThat(result.content())
        .contains("/** A2 */\n  public void f(java.lang.String s) {}")
        .doesNotContain("public void f(String s)");
  }

  @Test
  void differentSimpleNamesAreDifferentSignatures() {
    var base = "package com.example;\n\npublic class A {\n  void f(java.util.List l) {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  void f(java.awt.Point p) {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.added()).containsExactly("f(java.awt.Point)");
  }

  // ---- Comments around members (fix round 1) ----

  @Test
  void javadocAfterAnnotationIsReplacedNotDuplicated() {
    var base =
        "package com.example;\n\npublic class A {\n  @Deprecated\n  /** Old. */\n"
            + "  public void m() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  public void m() {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.changed()).containsExactly("m()");
    assertThat(result.content()).isEqualTo(base.replace("/** Old. */", "/** New. */"));
  }

  @Test
  void commentOnTypeNeverLeaksIntoRejectionMessage() {
    var base =
        "package com.example;\n\npublic class A {\n  @Deprecated\n  /**\n   * Old.\n   */\n"
            + "  public void m() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  public int m() {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    assertThatThrownBy(() -> StubSplicer.splice(PATH, base, frag))
        .isInstanceOf(SpliceException.class)
        .hasMessageStartingWith(PATH + ": ")
        .hasMessageContaining("'void' in the file")
        .hasMessageNotContaining("\n")
        .hasMessageNotContaining("Old");
  }

  @Test
  void commentsInsideParameterTypesDoNotAffectSignatures() {
    var base =
        "package com.example;\n\npublic class A {\n  /** Old. */\n"
            + "  public void m(/* the text */ String text) {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  public void m(String text) {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.changed()).containsExactly("m(String)");
    assertThat(result.added()).isEmpty();
  }

  @Test
  void javadocBeforeALineCommentIsReplacedNotDuplicated() {
    var base =
        "package com.example;\n\npublic class A {\n  void a() {}\n\n  /** Old. */\n  // note\n"
            + "  @Deprecated\n  void m() {\n    a();\n  }\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  void m() {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content()).isEqualTo(base.replace("/** Old. */", "/** New. */"));
  }

  @Test
  void otherMembersJavadocIsNotTaken() {
    var base =
        "package com.example;\n\npublic class A {\n  /** Field. */\n  int x;\n  // note\n"
            + "  void m() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  /** New. */\n  void m() {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic class A {\n  /** Field. */\n  int x;\n  // note\n"
                + "  /** New. */\n  void m() {}\n}\n");
  }

  @Test
  void addedMemberWithJavadocAfterAnnotationIsNotDuplicated() {
    var base = "package com.example;\n\npublic class A {\n  void a() {}\n}\n";
    var frag =
        "package com.example;\n\npublic class A {\n  @Deprecated\n  /** New. */\n  void b() {\n"
            + "    throw new UnsupportedOperationException();\n  }\n}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content().split("New\\.", -1)).hasSize(2);
    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic class A {\n  void a() {}\n\n  @Deprecated\n"
                + "  /** New. */\n  void b() {\n    throw new UnsupportedOperationException();\n"
                + "  }\n}\n");
  }

  // ---- Enums and records (fix round 1) ----

  static final String ADD_B =
      "  /** B. */\n  public int b() {\n    throw new UnsupportedOperationException();\n  }\n";

  @Test
  void addsToEnumWithoutSemicolon() {
    var base = "package com.example;\n\npublic enum A {\n  X,\n  Y\n}\n";
    var frag = "package com.example;\n\npublic enum A {\n  ;\n\n" + ADD_B + "}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic enum A {\n  X,\n  Y;\n\n  /** B. */\n"
                + "  public int b() {\n    throw new UnsupportedOperationException();\n  }\n}\n");
    assertThat(result.added()).containsExactly("b()");
  }

  @Test
  void addsToEnumWithTrailingCommaAndComment() {
    var base = "package com.example;\n\npublic enum A {\n  X,\n  Y, // last\n}\n";
    var frag = "package com.example;\n\npublic enum A {\n  ;\n" + ADD_B + "}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .startsWith(
            "package com.example;\n\npublic enum A {\n  X,\n  Y,; // last\n\n  /** B. */\n");
  }

  @Test
  void addsToEnumWithSemicolon() {
    var base = "package com.example;\n\npublic enum A {\n  X,\n  Y;\n}\n";
    var frag = "package com.example;\n\npublic enum A {\n  ;\n" + ADD_B + "}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic enum A {\n  X,\n  Y;\n\n  /** B. */\n"
                + "  public int b() {\n    throw new UnsupportedOperationException();\n  }\n}\n");
  }

  @Test
  void addsToEnumWithMembers() {
    var base =
        "package com.example;\n\npublic enum A {\n  X;\n\n  /** Old. */\n  public int a() {\n"
            + "    return 1;\n  }\n}\n";
    var frag = "package com.example;\n\npublic enum A {\n  ;\n" + ADD_B + "}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content()).isEqualTo(base.replace("  }\n}\n", "  }\n\n" + ADD_B + "}\n"));
  }

  @Test
  void addsToEnumWithoutConstants() {
    var base = "package com.example;\n\npublic enum A {\n}\n";
    var frag = "package com.example;\n\npublic enum A {\n  ;\n" + ADD_B + "}\n";

    var result = StubSplicer.splice("src/main/java/com/example/A.java", base, frag);

    assertThat(result.content())
        .isEqualTo("package com.example;\n\npublic enum A {;\n\n" + ADD_B + "}\n");
  }

  @Test
  void rejectsEnumConstantsInFragment() {
    var base = "package com.example;\n\npublic enum Csv {\n  X\n}\n";
    assertRejected(base, "package com.example;\n\npublic enum Csv {\n  X, Z\n}\n", "enum constant");
  }

  @Test
  void addsAndChangesRecordMethods() {
    var base =
        "package com.example;\n\npublic record P(int x, int y) {\n  /** Old. */\n"
            + "  public int sum() {\n    return x + y;\n  }\n}\n";
    var frag =
        "package com.example;\n\npublic record P(int x, int y) {\n  /** New. */\n"
            + "  public int sum() {\n    throw new UnsupportedOperationException();\n  }\n"
            + ADD_B
            + "}\n";

    var result = StubSplicer.splice("src/main/java/com/example/P.java", base, frag);

    assertThat(result.changed()).containsExactly("sum()");
    assertThat(result.added()).containsExactly("b()");
    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic record P(int x, int y) {\n  /** New. */\n"
                + "  public int sum() {\n    return x + y;\n  }\n\n"
                + ADD_B
                + "}\n");
  }

  static final String RECORD_PATH = "src/main/java/com/example/P.java";

  static final String COMPACT_FRAGMENT =
      "package com.example;\n\npublic record P(int x) {\n  /** New: x must be even. */\n"
          + "  public P {\n"
          + UOE
          + "  }\n}\n";

  /** A changed compact constructor keeps today's body and takes the fragment's Javadoc. */
  @Test
  void changedCompactConstructorKeepsItsBody() {
    var base =
        "package com.example;\n\npublic record P(int x) {\n  /** Old. */\n  public P {\n"
            + "    if (x < 0) {\n      throw new IllegalArgumentException();\n    }\n  }\n}\n";

    var result = StubSplicer.splice(RECORD_PATH, base, COMPACT_FRAGMENT);

    assertThat(result.changed()).containsExactly("P (compact constructor)");
    assertThat(result.added()).isEmpty();
    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic record P(int x) {\n  /** New: x must be even. */\n"
                + "  public P {\n    if (x < 0) {\n      throw new IllegalArgumentException();\n"
                + "    }\n  }\n}\n");
  }

  @Test
  void compactConstructorIsAddedWhenTheRecordHasNone() {
    var base =
        "package com.example;\n\npublic record P(int x) {\n  int twice() {\n    return 2 * x;\n  }\n}\n";

    var result = StubSplicer.splice(RECORD_PATH, base, COMPACT_FRAGMENT);

    assertThat(result.added()).containsExactly("P (compact constructor)");
    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\npublic record P(int x) {\n  int twice() {\n    return 2 * x;\n  }\n\n"
                + "  /** New: x must be even. */\n  public P {\n"
                + UOE
                + "  }\n}\n");
  }

  @Test
  void compactConstructorModifiersMustMatch() {
    var base = "package com.example;\n\npublic record P(int x) {\n  P {}\n}\n";
    assertThatThrownBy(() -> StubSplicer.splice(RECORD_PATH, base, COMPACT_FRAGMENT))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining(
            "P (compact constructor) has modifiers 'public' in the fragment but 'none'");
  }

  @Test
  void compactConstructorBodyMustBeTheThrow() {
    var base = "package com.example;\n\npublic record Csv(int x) {}\n";
    assertRejected(
        base,
        "package com.example;\n\npublic record Csv(int x) {\n  public Csv {\n    x = 1;\n  }\n}\n",
        "is not exactly");
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

  // ---- Abstract members ----

  static final String REPO_BASE =
      "package com.example;\n\nimport java.util.List;\n\npublic interface Repo {\n"
          + "  /** Old. */\n  List<String> findAll();\n\n"
          + "  default int size() {\n    return 0;\n  }\n}\n";

  static String repoFragment(String body) {
    return "package com.example;\n\nimport java.util.List;\nimport java.util.Optional;\n\n"
        + "public interface Repo {\n"
        + body
        + "}\n";
  }

  @Test
  void abstractInterfaceMethodIsAdded() {
    var result =
        StubSplicer.splice(
            PATH,
            REPO_BASE,
            repoFragment("  /** By tag. */\n  Optional<String> findByTag(String tag);\n"));

    assertThat(result.added()).containsExactly("findByTag(String)");
    assertThat(result.content())
        .contains("  /** By tag. */\n  Optional<String> findByTag(String tag);\n}\n")
        .doesNotContain("UnsupportedOperationException");
  }

  @Test
  void abstractMethodInAbstractClassIsAdded() {
    var base = "package com.example;\n\npublic abstract class A {\n  void a() {}\n}\n";
    var frag = "package com.example;\n\npublic abstract class A {\n  abstract void b();\n}\n";

    var result = StubSplicer.splice(PATH, base, frag);

    assertThat(result.added()).containsExactly("b()");
    assertThat(result.content()).contains("  abstract void b();\n}\n");
  }

  @Test
  void changedAbstractMethodKeepsDeclarationTakesJavadoc() {
    var result =
        StubSplicer.splice(
            PATH, REPO_BASE, repoFragment("  /** New docs. */\n  List<String> findAll();\n"));

    assertThat(result.changed()).containsExactly("findAll()");
    assertThat(result.content())
        .contains("  /** New docs. */\n  List<String> findAll();")
        .doesNotContain("Old.");
  }

  @Test
  void abstractVersusConcreteMismatchRejected() {
    assertThatThrownBy(() -> StubSplicer.splice(PATH, REPO_BASE, repoFragment("  int size();\n")))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining("is abstract in the fragment but not in the file");
    assertThatThrownBy(() ->
            StubSplicer.splice(
                PATH, REPO_BASE, repoFragment("  List<String> findAll() {\n" + UOE + "  }\n")))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining("is abstract in the file but not in the fragment");
  }

  @Test
  void abstractMemberInConcreteClassRejected() {
    assertThatThrownBy(() ->
            StubSplicer.splice(PATH, BASE, csvFragment("  public static int count(String t);\n")))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining(
            "only interfaces and abstract classes can declare a method without a body");
  }

  static final String SHAPE_BASE =
      "package com.example;\n\npublic abstract class Shape {\n"
          + "  /** Old. */\n  public abstract double area();\n\n"
          + "  public String name() {\n    return \"shape\";\n  }\n}\n";

  static String shapeFragment(String body) {
    return "package com.example;\n\npublic abstract class Shape {\n" + body + "}\n";
  }

  @Test
  void changedAbstractMethodInAbstractClassKeepsDeclarationTakesJavadoc() {
    var result =
        StubSplicer.splice(
            PATH,
            SHAPE_BASE,
            shapeFragment("  /** New docs. */\n  public abstract double area();\n"));

    assertThat(result.changed()).containsExactly("area()");
    assertThat(result.content())
        .contains("  /** New docs. */\n  public abstract double area();")
        .contains("return \"shape\";")
        .doesNotContain("Old.");
  }

  @Test
  void abstractVersusConcreteMismatchInAbstractClassRejected() {
    assertThatThrownBy(() ->
            StubSplicer.splice(
                PATH, SHAPE_BASE, shapeFragment("  public abstract String name();\n")))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining("name() is abstract in the fragment but not in the file");
    assertThatThrownBy(() ->
            StubSplicer.splice(
                PATH, SHAPE_BASE, shapeFragment("  public double area() {\n" + UOE + "  }\n")))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining("area() is abstract in the file but not in the fragment");
  }

  @Test
  void bodylessConcreteMethodInAbstractClassRejected() {
    assertRejected(
        SHAPE_BASE,
        shapeFragment("  public double perimeter();\n"),
        "perimeter() has no body; in an abstract class only an abstract method may omit its body");
  }

  @Test
  void bodylessStaticMethodInInterfaceRejected() {
    assertThatThrownBy(
            () -> StubSplicer.splice(PATH, REPO_BASE, repoFragment("  static int other();\n")))
        .isInstanceOf(SpliceException.class)
        .hasMessageContaining("is not exactly");
  }
}
