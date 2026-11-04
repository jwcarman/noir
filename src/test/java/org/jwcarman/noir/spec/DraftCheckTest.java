package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftCheckTest {
  static final String STUB_PATH = "src/main/java/com/example/api/Foo.java";
  @TempDir Path draft;

  @BeforeEach
  void setUp() throws IOException {
    SpecJson.write(Specs.valid().withBaseSha(null), draft.resolve("spec.json"));
    stub(STUB_PATH, "package com.example.api;\n\npublic interface Foo {}\n");
  }

  void stub(String path, String content) throws IOException {
    var f = draft.resolve("stubs").resolve(path);
    Files.createDirectories(f.getParent());
    Files.writeString(f, content);
  }

  @Test
  void checkPassesACompleteDraft() {
    assertThat(DraftCheck.check(draft)).isEmpty();
  }

  @Test
  void checkIgnoresMissingBaseSha() {
    assertThat(SpecJson.read(draft.resolve("spec.json")).baseSha()).isNull();
    assertThat(DraftCheck.check(draft)).noneMatch(p -> p.contains("baseSha"));
  }

  @Test
  void checkReportsTodoPlaceholders() {
    var spec = Specs.valid();
    var reqs =
        List.of(
            new Requirement("R1", "TODO", List.of(new Example("TODO given", "a when", "a then"))));
    SpecJson.write(spec.withRequirements(reqs).withBaseSha(null), draft.resolve("spec.json"));
    assertThat(DraftCheck.check(draft))
        .containsExactlyInAnyOrder(
            "R1 statement still contains a TODO placeholder",
            "R1 example 1 'given' still contains a TODO placeholder");
  }

  @Test
  void checkReportsMissingStub() throws IOException {
    Files.delete(draft.resolve("stubs").resolve(STUB_PATH));
    assertThat(DraftCheck.check(draft))
        .containsExactly("stub " + STUB_PATH + " is missing from stubs/");
  }

  @Test
  void checkReportsUnlistedStub() throws IOException {
    stub("src/main/java/com/example/api/Bar.java", "package com.example.api;\nclass Bar {}\n");
    assertThat(DraftCheck.check(draft))
        .containsExactly("stub src/main/java/com/example/api/Bar.java is not listed in apiStubs");
  }

  @Test
  void checkReportsPackageMismatch() throws IOException {
    stub(STUB_PATH, "package com.example.other;\n\npublic interface Foo {}\n");
    assertThat(DraftCheck.check(draft))
        .singleElement()
        .asString()
        .contains("declares package 'com.example.other'")
        .contains("implies 'com.example.api'");
  }

  @Test
  void checkReportsUnparseableStub() throws IOException {
    stub(STUB_PATH, "package com.example.api;\n\npublic interface Foo {\n");
    assertThat(DraftCheck.check(draft))
        .containsExactly("stub " + STUB_PATH + " does not parse as Java");
  }

  @Test
  void checkRejectsSymlinkedStub() throws IOException {
    var target = draft.resolve("elsewhere.java");
    Files.writeString(target, "package com.example.api;\n\npublic interface Foo {}\n");
    var link = draft.resolve("stubs").resolve(STUB_PATH);
    Files.delete(link);
    Files.createSymbolicLink(link, target);
    assertThat(DraftCheck.check(draft))
        .containsExactly("stub " + STUB_PATH + " must not be a symbolic link");
  }
}
