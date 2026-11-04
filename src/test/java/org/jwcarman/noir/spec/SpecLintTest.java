package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpecLintTest {
  @Test
  void validSpecHasNoProblems() {
    assertThat(SpecLint.lint(Specs.valid())).isEmpty();
  }

  @Test
  void requiresSchemaVersion1() {
    assertThat(SpecLint.lint(Specs.valid().withSchemaVersion(2)))
        .containsExactly("schemaVersion must be 1");
  }

  @Test
  void requiresValidId() {
    assertThat(SpecLint.lint(Specs.valid().withId("bogus")))
        .containsExactly("id must be a valid noir item id");
  }

  @Test
  void requiresBaseSha() {
    assertThat(SpecLint.lint(Specs.valid().withBaseSha(null)))
        .containsExactly("baseSha must be a 40-character hex commit SHA");
  }

  @Test
  void requiresOwnerSlashRepo() {
    assertThat(SpecLint.lint(Specs.valid().withRepo("noslash")))
        .containsExactly("repo must be owner/name");
  }

  @Test
  void requirementIdsSequential() {
    assertThat(SpecLint.lint(Specs.withRequirementIds(Specs.valid(), "R1", "R3")))
        .containsExactly("requirement ids must be R1..Rn in order; found R3 at position 2");
  }

  @Test
  void everyRequirementNeedsExample() {
    assertThat(SpecLint.lint(Specs.withNoExamplesOn(Specs.valid(), "R1")))
        .containsExactly("R1 has no examples");
  }

  @Test
  void needsAtLeastOneRequirementAndStub() {
    assertThat(SpecLint.lint(Specs.valid().withRequirements(List.of()).withApiStubs(List.of())))
        .containsExactlyInAnyOrder(
            "at least one requirement is required", "at least one API stub is required");
  }

  @Test
  void qualityBarInRange() {
    assertThat(SpecLint.lint(Specs.valid().withQualityBar(new QualityBar(1.2, 0))))
        .containsExactlyInAnyOrder(
            "qualityBar.lineCoverage must be in (0, 1]",
            "qualityBar.mutationScore must be in (0, 1]");
  }

  @Test
  void stubPathsMustBeMainJavaSources() {
    assertThat(SpecLint.lint(Specs.valid().withApiStubs(List.of("../etc/passwd"))))
        .containsExactly(
            "apiStubs entry ../etc/passwd must be a relative path under src/main/java/ ending in .java");
    assertThat(SpecLint.lint(Specs.valid().withApiStubs(List.of("src/main/java/../../x.java"))))
        .hasSize(1);
  }

  @Test
  void blankFieldsReported() {
    var spec =
        Specs.valid()
            .withTitle(" ")
            .withRequirements(
                List.of(new Requirement("R1", "s", List.of(new Example("g", "w", "")))));
    assertThat(SpecLint.lint(spec))
        .containsExactlyInAnyOrder(
            "title must not be blank", "R1 example 1 'then' must not be blank");
  }
}
