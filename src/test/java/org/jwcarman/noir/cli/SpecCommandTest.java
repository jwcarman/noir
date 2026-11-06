package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.Specs;

class SpecCommandTest {
  @TempDir Path tmp;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();
  final FakeGitHub github = new FakeGitHub().withNoirLabels("o/r");

  int run(String... args) {
    var ops =
        new NoirCli.Operations(
            home ->
                (once, listener) -> {
                  throw new AssertionError("run must not be invoked");
                },
            () -> github,
            Set::of);
    return NoirCli.execute(
        new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), ops, args);
  }

  static String cleanBody() {
    var spec = Specs.counter();
    return SpecRenderer.issueBody(spec, Specs.stubsFor(spec));
  }

  Path write(String body) throws IOException {
    return Files.writeString(tmp.resolve("spec.md"), body);
  }

  @Test
  void checkCleanFilePrintsOk() throws IOException {
    var file = write(cleanBody());

    assertThat(run("spec", "check", file.toString(), "--title", Specs.counter().title())).isZero();
    assertThat(out.toString()).isEqualTo("OK\n");
  }

  @Test
  void checkReportsEveryProblemAndExits1() throws IOException {
    var file = write("## Intent\n\nx\n\n## Bogus\n\ny\n");

    assertThat(run("spec", "check", file.toString(), "--title", "t")).isEqualTo(1);
    var lines = out.toString().lines().toList();
    assertThat(lines).hasSizeGreaterThan(1);
    assertThat(lines).allMatch(l -> l.startsWith("line "));
  }

  @Test
  void checkFileRequiresTitle() throws IOException {
    var file = write(cleanBody());

    assertThat(run("spec", "check", file.toString())).isEqualTo(2);
    assertThat(err.toString()).contains("--title");
  }

  @Test
  void checkIssueUsesTitleAndBody() {
    int n = github.createIssue("o/r", Specs.counter().title(), cleanBody(), List.of());

    assertThat(run("spec", "check", "--issue", "o/r#" + n)).isZero();
    assertThat(out.toString()).isEqualTo("OK\n");
  }

  @Test
  void checkClosedIssueFails() {
    int n = github.createIssue("o/r", Specs.counter().title(), cleanBody(), List.of());
    github.closeIssue(n);

    assertThat(run("spec", "check", "--issue", "o/r#" + n)).isEqualTo(1);
    assertThat(out.toString()).contains("issue is closed");
  }

  @Test
  void checkBadIssueReference() {
    assertThat(run("spec", "check", "--issue", "nonsense")).isEqualTo(2);
    assertThat(err.toString()).contains("expected owner/repo#number");
  }

  @Test
  void templatePassesParseButFailsLintOnTodo() {
    assertThat(run("spec", "template", "--title", "My thing")).isZero();
    var body = out.toString();

    var parsed = IssueSpec.parse("My thing", body);
    assertThat(IssueSpec.lint(parsed)).anyMatch(p -> p.contains("TODO"));
  }

  private static final String FOO = "src/main/java/com/example/Foo.java";
  private static final String FOO_TEST = "src/test/java/com/example/FooTest.java";
  private static final String BASE =
      "package com.example;\n\npublic class Foo {\n  public int a() {\n    return 1;\n  }\n}\n";
  private static final String GOOD_FRAGMENT =
      "package com.example;\n\npublic class Foo {\n  public int b() {\n"
          + "    throw new UnsupportedOperationException();\n  }\n}\n";

  private Path cloneRepo() {
    var origin =
        org.jwcarman.noir.git.GitFixtures.originWithFiles(
            tmp.resolve("origins"),
            "o/r",
            java.util.Map.of(FOO, BASE, FOO_TEST, "package com.example;\n\nclass FooTest {}\n"));
    return org.jwcarman.noir.git.GitFixtures.cloneOf(origin, tmp.resolve("clone"));
  }

  private Path modifySpec(String target, String fragment, String test) throws IOException {
    var spec =
        Specs.counter()
            .withApiStubs(List.of(target))
            .withModifies(List.of(target))
            .withTestsToUpdate(List.of(test));
    var body =
        SpecRenderer.issueBody(
            spec, List.of(new org.jwcarman.noir.render.StubSource(target, fragment)));
    return write(body);
  }

  @Test
  void repoOptionSplicesModifyTargets() throws IOException {
    var repo = cloneRepo();
    var file = modifySpec(FOO, GOOD_FRAGMENT, FOO_TEST);

    assertThat(run("spec", "check", file.toString(), "--title", "t", "--repo", repo.toString()))
        .isZero();
    assertThat(out.toString()).isEqualTo("OK\n");
  }

  @Test
  void repoOptionReportsSpliceFailure() throws IOException {
    var repo = cloneRepo();
    var file = modifySpec(FOO, "package com.example;\n\npublic class Nope {}\n", FOO_TEST);

    assertThat(run("spec", "check", file.toString(), "--title", "t", "--repo", repo.toString()))
        .isEqualTo(1);
    assertThat(out.toString()).contains(FOO).doesNotContain("OK");
  }

  @Test
  void repoOptionReportsMissingModifyTargetAndTest() throws IOException {
    var repo = cloneRepo();
    var file =
        modifySpec(
            "src/main/java/com/example/Gone.java",
            GOOD_FRAGMENT.replace("Foo", "Gone"),
            "src/test/java/com/example/GoneTest.java");

    assertThat(run("spec", "check", file.toString(), "--title", "t", "--repo", repo.toString()))
        .isEqualTo(1);
    var lines = out.toString().lines().toList();
    assertThat(lines).anyMatch(l -> l.contains("Gone.java") && !l.contains("GoneTest"));
    assertThat(lines).anyMatch(l -> l.contains("GoneTest.java"));
  }

  @Test
  void repoOptionReportsExistingNewFileStub() throws IOException {
    var repo = cloneRepo();
    var spec = Specs.counter().withApiStubs(List.of(FOO));
    var file =
        write(
            SpecRenderer.issueBody(
                spec, List.of(new org.jwcarman.noir.render.StubSource(FOO, BASE))));

    assertThat(run("spec", "check", file.toString(), "--title", "t", "--repo", repo.toString()))
        .isEqualTo(1);
    assertThat(out.toString()).contains(FOO).contains("already exists");
  }

  @Test
  void repoOptionWithoutOriginHeadFails() throws IOException {
    var repo = cloneRepo();
    org.jwcarman.noir.git.GitFixtures.git(repo, "remote", "set-head", "origin", "-d");
    var file = write(cleanBody());

    assertThat(run("spec", "check", file.toString(), "--title", "t", "--repo", repo.toString()))
        .isEqualTo(1);
    assertThat(out.toString() + err)
        .contains("origin/HEAD is not set; run `git remote set-head origin --auto`");
  }
}
