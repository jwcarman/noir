package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.render.StubSource;

class StubLintTest {
  static final String PATH = "src/main/java/com/example/api/Foo.java";

  static StubSource stub(String path, String content) {
    return new StubSource(path, content);
  }

  @Test
  void acceptsJava25Syntax() {
    assertThat(
            StubLint.lint(
                List.of(
                    stub(
                        PATH,
                        "package com.example.api;\n\nimport module java.base;\n\n"
                            + "public interface Foo {\n  default int f() {\n    int n = 0;\n"
                            + "    for (var _ : List.of(1)) {\n      n++;\n    }\n"
                            + "    return n;\n  }\n}\n"))))
        .isEmpty();
  }

  @Test
  void acceptsAValidStub() {
    assertThat(StubLint.lint(List.of(stub(PATH, "package com.example.api;\n\ninterface Foo {}\n"))))
        .isEmpty();
  }

  @Test
  void reportsPackageMismatch() {
    assertThat(StubLint.lint(List.of(stub(PATH, "package com.example.other;\ninterface Foo {}"))))
        .singleElement()
        .asString()
        .contains("declares package 'com.example.other'")
        .contains("implies 'com.example.api'");
  }

  @Test
  void reportsPathOutsideSrcMainJava() {
    assertThat(StubLint.lint(List.of(stub("src/test/java/A.java", "class A {}"))))
        .singleElement()
        .asString()
        .contains("src/test/java/A.java")
        .contains("src/main/java/");
  }

  @Test
  void reportsDotDot() {
    assertThat(StubLint.lint(List.of(stub("src/main/java/../A.java", "class A {}"))))
        .singleElement()
        .asString()
        .contains("src/main/java/../A.java");
  }

  @Test
  void reportsDuplicatePath() {
    var s = stub("src/main/java/A.java", "class A {}");
    assertThat(StubLint.lint(List.of(s, s))).anyMatch(p -> p.contains("duplicate"));
  }

  @Test
  void reportsUnparseableStub() {
    assertThat(StubLint.lint(List.of(stub("src/main/java/A.java", "class A {"))))
        .singleElement()
        .asString()
        .contains("does not parse as Java");
  }
}
