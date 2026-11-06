package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.jwcarman.noir.render.SpecRenderer;

class IssueSpecTest {
  static String body() {
    return SpecRenderer.issueBody(Specs.counter(), Specs.stubsFor(Specs.counter()));
  }

  static IssueSpecException fail(String body) {
    return catchThrowableOfType(IssueSpecException.class, () -> IssueSpec.parse("t", body));
  }

  static String bodyWithout(String heading) {
    var b = body();
    var start = b.indexOf(heading);
    var next = b.indexOf("\n## ", start + 1);
    var end = next < 0 ? b.indexOf("\n---\n") + 1 : next + 1;
    return b.substring(0, start) + b.substring(end);
  }

  @Test
  void roundTripsEveryFixture() {
    for (var spec : Specs.all()) {
      var stubs = Specs.stubsFor(spec);
      var parsed = IssueSpec.parse(spec.title(), SpecRenderer.issueBody(spec, stubs));
      assertThat(parsed.toSpec(spec.id(), spec.repo(), spec.baseSha())).isEqualTo(spec);
      assertThat(parsed.stubs()).isEqualTo(stubs);
    }
  }

  @Test
  void parsesCrlfBody() {
    var crlf = body().replace("\n", "\r\n");
    assertThat(IssueSpec.parse("Add a Counter", crlf).requirements())
        .hasSize(Specs.counter().requirements().size());
  }

  @Test
  void footerIsOptional() {
    var withFooter = body();
    var without = withFooter.substring(0, withFooter.indexOf("\n---\n"));
    assertThat(IssueSpec.parse("t", without)).isEqualTo(IssueSpec.parse("t", withFooter));
  }

  @Test
  void normalizeTrimsTrailingWhitespace() {
    assertThat(IssueSpec.normalize("a\r\nb\r\n\r\n  \n")).isEqualTo("a\nb\n");
  }

  @Test
  void missingSectionReportsLine() {
    var ex = fail(bodyWithout("## Constraints"));
    assertThat(ex.problems()).anyMatch(p -> p.matches("line \\d+: missing section ## Constraints"));
  }

  @Test
  void sectionsOutOfOrder() {
    var b =
        body()
            .replace("## API contract", "## TMP")
            .replace("## Requirements", "## API contract")
            .replace("## TMP", "## Requirements");
    assertThat(fail(b).problems())
        .anyMatch(p -> p.matches("line \\d+: ## Requirements must come after ## API contract"));
  }

  @Test
  void unclosedFence() {
    var b = body();
    var open = b.indexOf("````java");
    var closeIdx = b.indexOf("\n````\n", open);
    var broken = b.substring(0, closeIdx + 1) + b.substring(closeIdx + 6);
    var fenceLine = b.substring(0, b.indexOf("````java")).split("\n", -1).length;
    assertThat(fail(broken).problems())
        .contains("line " + fenceLine + ": code fence opened here is never closed");
  }

  @Test
  void exampleWithoutThen() {
    var b = body().replaceFirst(" \\*\\*then\\*\\* [^\n]*", "");
    assertThat(fail(b).problems())
        .anyMatch(p ->
            p.matches(
                "line \\d+: example must be - \\*\\*Given\\*\\* … \\*\\*when\\*\\* … \\*\\*then\\*\\* …"));
  }

  @Test
  void badQualityBar() {
    var b = body().replace("Line coverage ≥ 80%", "Line coverage ≥ eighty%");
    assertThat(fail(b).problems())
        .anyMatch(p -> p.matches("line \\d+: expected - Line coverage ≥ <n>%"));
  }

  @Test
  void constraintsNone() {
    assertThat(IssueSpec.parse("t", body()).constraints()).isEmpty();
    assertThat(body()).contains("_None._");
  }

  @Test
  void textBeforeIntent() {
    assertThat(fail("# Add a Counter\n\n" + body()).problems())
        .contains("line 1: unexpected text before ## Intent");
  }

  @Test
  void collectsMultipleProblems() {
    var b = body().replace("Line coverage ≥ 80%", "bad").replace("Max CRAP", "Max");
    assertThat(fail(b).problems()).hasSizeGreaterThan(1);
  }

  @Test
  void lintRejectsTodo() {
    var parsed = IssueSpec.parse("Add X", SpecRenderer.template("Add X"));
    assertThat(IssueSpec.lint(parsed)).anyMatch(p -> p.contains("TODO"));
  }

  @Test
  void lintAcceptsAGoodSpec() {
    var spec = Specs.valid();
    var parsed = IssueSpec.parse(spec.title(), SpecRenderer.issueBody(spec, Specs.stubsFor(spec)));
    assertThat(IssueSpec.lint(parsed)).isEmpty();
  }

  @Test
  void handWrittenStubEndsWithNewline() {
    var b = body().replace("{}\n\n````\n", "{}\n````\n");
    assertThat(b).doesNotContain("{}\n\n````");

    var stubs = IssueSpec.parse("t", b).stubs();

    assertThat(stubs).isNotEmpty().allSatisfy(s -> assertThat(s.content()).endsWith("}\n"));
    assertThat(stubs).isEqualTo(Specs.stubsFor(Specs.counter()));
  }

  @Test
  void stubNewlineIsNeverDoubled() {
    // A blank line before the closing fence (as bodies rendered before this change have).
    var b = body().replace("{}\n````\n", "{}\n\n````\n");
    assertThat(b).contains("{}\n\n````");

    assertThat(IssueSpec.parse("t", b).stubs()).isEqualTo(Specs.stubsFor(Specs.counter()));
  }

  @Test
  void trailingWhitespaceOnLinesIsIgnored() {
    var b =
        body()
            .replace("### R1\n", "### R1 \n")
            .replace("- Line coverage ≥ 80%\n", "- Line coverage ≥ 80% \t\n")
            .replace("{}\n````\n", "{}\n````  \n");
    assertThat(b).contains("### R1 \n", "80% \t\n", "````  \n");

    assertThat(IssueSpec.parse("t", b)).isEqualTo(IssueSpec.parse("t", body()));
  }

  @Test
  void normalizeKeepsLinesInsideStubFencesVerbatim() {
    var b = "## API contract\n\n### X.java\n\n```java\nint a;  \n```  \n\n## Requirements \n";

    assertThat(IssueSpec.normalize(b))
        .isEqualTo("## API contract\n\n### X.java\n\n```java\nint a;  \n```\n\n## Requirements\n");
  }

  @Test
  void lintQualityBarUsesBodyWording() {
    var b =
        body()
            .replace("Line coverage ≥ 80%", "Line coverage ≥ 0%")
            .replace("Mutation score ≥ 70%", "Mutation score ≥ 150%")
            .replaceFirst("Max CRAP score per method ≤ \\d+", "Max CRAP score per method ≤ 0");

    var problems = IssueSpec.lint(IssueSpec.parse("t", b));

    assertThat(problems)
        .containsExactly(
            "Line coverage must be between 1% and 100%",
            "Mutation score must be between 1% and 100%",
            "Max CRAP score per method must be at least 1");
  }

  @Test
  void lintNeverUsesInternalFieldNames() {
    var parsed = IssueSpec.parse("t", body());
    var broken =
        new IssueSpec(
            "t",
            parsed.intent(),
            parsed.requirements(),
            parsed.constraints(),
            null,
            java.util.List.of(new org.jwcarman.noir.render.StubSource("lib/Foo.txt", "x\n")),
            null,
            null);
    var bad =
        new IssueSpec(
            "t",
            parsed.intent(),
            parsed.requirements(),
            parsed.constraints(),
            new QualityBar(0, 2, 0),
            parsed.stubs(),
            null,
            null);

    var problems = new java.util.ArrayList<>(IssueSpec.lint(broken));
    problems.addAll(IssueSpec.lint(bad));

    assertThat(problems).hasSizeGreaterThanOrEqualTo(4);
    assertThat(problems)
        .noneMatch(p ->
            p.contains("qualityBar")
                || p.contains("apiStubs")
                || p.contains("modifies")
                || p.contains("testsToUpdate")
                || p.contains("schemaVersion")
                || p.contains("requirements["));
  }

  @Test
  void lintNeverMentionsPlaceholders() {
    var spec = Specs.withRequirementIds(Specs.valid(), "R2", "R1");
    var parsed = IssueSpec.parse(spec.title(), SpecRenderer.issueBody(spec, Specs.stubsFor(spec)));
    var problems = IssueSpec.lint(parsed);
    assertThat(problems).isNotEmpty();
    assertThat(problems)
        .noneMatch(p -> p.contains(Specs.ID) || p.contains("demo") || p.contains("0123456789"));
    assertThat(problems).noneMatch(p -> p.contains("owner/") || p.contains("nr-"));
  }

  private static final String OLD = "src/main/java/a/B.java";
  private static final String TEST = "src/test/java/a/BTest.java";

  @Test
  void parsesModifyHeading() {
    var b =
        body().replace("### src/main/java/com/example/Counter.java", "### " + OLD + " (modify)");
    var parsed = IssueSpec.parse("t", b);
    assertThat(parsed.modifies()).containsExactly(OLD);
    assertThat(parsed.stubs()).extracting(s -> s.path()).containsExactly(OLD);
  }

  @Test
  void parsesTestsToUpdate() {
    var b =
        body()
            .replace("## Constraints", "## Tests to update\n\n- `" + TEST + "`\n\n## Constraints");
    assertThat(IssueSpec.parse("t", b).testsToUpdate()).containsExactly(TEST);
  }

  @Test
  void testsToUpdateOutOfOrderIsAProblem() {
    var b =
        body()
            .replace("## Quality bar", "## Tests to update\n\n- `" + TEST + "`\n\n## Quality bar");
    assertThat(fail(b).problems())
        .anyMatch(p -> p.matches("line \\d+: ## Constraints must come after ## Tests to update"));
  }

  @Test
  void testsToUpdateEntryMustBeBacktickedPath() {
    var b =
        body().replace("## Constraints", "## Tests to update\n\n- " + TEST + "\n\n## Constraints");
    assertThat(fail(b).problems()).anyMatch(p -> p.contains("line ") && p.contains("- `<path>`"));
  }

  @Test
  void roundTripWithModifyAndTests() {
    var spec = Specs.withModifyAndTests();
    var stubs = Specs.stubsFor(spec);
    var body = SpecRenderer.issueBody(spec, stubs);
    assertThat(body).contains("### src/main/java/com/example/Old.java (modify)\n");
    assertThat(body).doesNotContain("New.java (modify)");
    assertThat(body).contains("## Tests to update\n\n- `src/test/java/com/example/OldTest.java`\n");
    var parsed = IssueSpec.parse(spec.title(), body);
    assertThat(parsed.toSpec(spec.id(), spec.repo(), spec.baseSha())).isEqualTo(spec);
    assertThat(parsed.stubs()).isEqualTo(stubs);
    assertThat(SpecRenderer.specBody(spec, stubs))
        .contains("(modify)")
        .contains("## Tests to update");
  }

  @Test
  void newFileOnlySpecRendersAsBefore() {
    var spec = Specs.counter();
    assertThat(body()).doesNotContain("(modify)").doesNotContain("Tests to update");
    assertThat(
            IssueSpec.parse("t", body())
                .toSpec(spec.id(), spec.repo(), spec.baseSha())
                .schemaVersion())
        .isEqualTo(1);
    assertThat(body())
        .contains("## Requirements\n\n### R1")
        .contains("\n\n## Constraints\n\n_None._\n\n## Quality bar\n");
  }
}
