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
  void lintNeverMentionsPlaceholders() {
    var spec = Specs.withRequirementIds(Specs.valid(), "R2", "R1");
    var parsed = IssueSpec.parse(spec.title(), SpecRenderer.issueBody(spec, Specs.stubsFor(spec)));
    var problems = IssueSpec.lint(parsed);
    assertThat(problems).isNotEmpty();
    assertThat(problems)
        .noneMatch(p -> p.contains(Specs.ID) || p.contains("demo") || p.contains("0123456789"));
    assertThat(problems).noneMatch(p -> p.contains("owner/") || p.contains("nr-"));
  }
}
