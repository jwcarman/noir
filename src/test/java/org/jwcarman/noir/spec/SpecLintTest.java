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
    assertThat(SpecLint.lint(Specs.valid().withSchemaVersion(3)))
        .containsExactly("schemaVersion must be 1 or 2");
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
    assertThat(SpecLint.lint(Specs.valid().withQualityBar(new QualityBar(1.2, 0, 30))))
        .containsExactlyInAnyOrder(
            "qualityBar.lineCoverage must be in (0, 1]",
            "qualityBar.mutationScore must be in (0, 1]");
  }

  @Test
  void maxCrapAtLeastOne() {
    assertThat(SpecLint.lint(Specs.valid().withQualityBar(new QualityBar(0.8, 0.7, 0))))
        .containsExactly("qualityBar.maxCrap must be at least 1");
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

  @Test
  void lintContentSkipsIdentityRules() {
    var spec = Specs.valid().withId(null).withRepo(null).withBaseSha(null);
    assertThat(SpecLint.lintContent(spec)).isEmpty();
  }

  private static Spec withMods(List<String> modifies, List<String> tests) {
    return Specs.valid()
        .withModifies(modifies)
        .withTestsToUpdate(tests)
        .withApiStubs(
            List.of("src/main/java/com/example/api/Foo.java", "src/main/java/com/example/B.java"));
  }

  @Test
  void modifyPathMustBeMainJava() {
    assertThat(SpecLint.lint(withMods(List.of("src/test/java/X.java"), List.of())))
        .anyMatch(p -> p.contains("src/test/java/X.java") && p.contains("src/main/java/"));
    assertThat(SpecLint.lint(withMods(List.of("src/main/java/com/example/B.txt"), List.of())))
        .isNotEmpty();
  }

  @Test
  void testPathMustBeTestJava() {
    assertThat(SpecLint.lint(withMods(List.of(), List.of("src/main/java/X.java"))))
        .anyMatch(p -> p.contains("Tests to update") && p.contains("src/test/java/"));
    assertThat(SpecLint.lint(withMods(List.of(), List.of("src/test/java/XTest.txt")))).isNotEmpty();
    assertThat(SpecLint.lint(withMods(List.of(), List.of("src/test/java/../../x.java"))))
        .isNotEmpty();
  }

  @Test
  void duplicatesAreRejected() {
    var m = "src/main/java/com/example/B.java";
    var t = "src/test/java/com/example/BTest.java";
    assertThat(SpecLint.lint(withMods(List.of(m, m), List.of())))
        .anyMatch(p -> p.contains("API contract") && p.contains("more than once"));
    assertThat(SpecLint.lint(withMods(List.of(), List.of(t, t))))
        .anyMatch(p -> p.contains("Tests to update") && p.contains("more than once"));
  }

  @Test
  void pathInBothListsIsRejected() {
    var p = "src/main/java/com/example/B.java";
    assertThat(SpecLint.lint(withMods(List.of(p), List.of(p)))).anyMatch(x -> x.contains(p));
  }

  @Test
  void validModifyAndTestsHaveNoProblems() {
    assertThat(
            SpecLint.lint(
                withMods(
                    List.of("src/main/java/com/example/B.java"),
                    List.of("src/test/java/com/example/BTest.java"))))
        .isEmpty();
  }

  @Test
  void lintMessagesUseIssueWording() {
    var m = "src/main/java/com/example/B.java";
    var all = new java.util.ArrayList<String>();
    all.addAll(SpecLint.lint(withMods(List.of("x", "x"), List.of("y", "y"))));
    all.addAll(SpecLint.lint(withMods(List.of(m), List.of(m))));
    assertThat(all).isNotEmpty();
    assertThat(all)
        .noneMatch(
            p -> p.contains("modifies") || p.contains("testsToUpdate") || p.contains("apiStubs"));
  }
}
