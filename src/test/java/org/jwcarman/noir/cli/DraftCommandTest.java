package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.QualityBar;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.SpecLint;

class DraftCommandTest {
  @TempDir Path tmp;

  @Test
  void draftInitCreatesSkeleton() throws Exception {
    var out = new StringWriter();
    int code =
        NoirCli.execute(
            new NoirHome(tmp),
            new PrintWriter(out),
            new PrintWriter(new StringWriter()),
            "draft",
            "init",
            "--repo",
            "jwcarman/demo",
            "--title",
            "Counter");
    assertThat(code).isZero();
    var id = out.toString().lines().findFirst().orElseThrow().replace("Created draft ", "");
    assertThat(ItemId.isValid(id)).isTrue();
    var dir = tmp.resolve("store/drafts").resolve(id);
    assertThat(out.toString()).contains(dir.toString());
    assertThat(Files.isDirectory(dir.resolve("stubs"))).isTrue();
    var spec = SpecJson.read(dir.resolve("spec.json"));
    assertThat(spec.id()).isEqualTo(id);
    assertThat(spec.repo()).isEqualTo("jwcarman/demo");
    assertThat(spec.title()).isEqualTo("Counter");
    assertThat(spec.qualityBar()).isEqualTo(new QualityBar(0.80, 0.70, 30));
    assertThat(spec.requirements()).singleElement().extracting(r -> r.id()).isEqualTo("R1");
    assertThat(SpecLint.lint(spec))
        .containsExactlyInAnyOrder(
            "baseSha must be a 40-character hex commit SHA", "at least one API stub is required");
  }

  private int run(StringWriter out, StringWriter err, String... args) {
    return NoirCli.execute(new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), args);
  }

  private String init(String repo, String title) {
    var out = new StringWriter();
    run(out, new StringWriter(), "draft", "init", "--repo", repo, "--title", title);
    return out.toString().lines().findFirst().orElseThrow().replace("Created draft ", "");
  }

  private void completeDraft(String id) throws Exception {
    var dir = tmp.resolve("store/drafts").resolve(id);
    var spec = org.jwcarman.noir.spec.Specs.valid().withId(id).withRepo("jwcarman/demo");
    SpecJson.write(spec, dir.resolve("spec.json"));
    var stub = dir.resolve("stubs/src/main/java/com/example/api/Foo.java");
    Files.createDirectories(stub.getParent());
    Files.writeString(stub, "package com.example.api;\n\npublic interface Foo {}\n");
  }

  @Test
  void initRecordsDraftCreated() {
    var id = init("jwcarman/demo", "Counter");
    var events =
        new org.jwcarman.noir.store.Store(new NoirHome(tmp))
            .readEvents(tmp.resolve("store/drafts").resolve(id));
    assertThat(events)
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo("draft_created");
          assertThat(e.attempt()).isZero();
          assertThat(e.itemId()).isEqualTo(id);
          assertThat(e.data())
              .containsEntry("repo", "jwcarman/demo")
              .containsEntry("title", "Counter");
        });
  }

  @Test
  void listWithNoDrafts() {
    var out = new StringWriter();
    assertThat(run(out, new StringWriter(), "draft", "list")).isZero();
    assertThat(out.toString().strip()).isEqualTo("No drafts.");
  }

  @Test
  void listShowsDrafts() {
    var a = init("jwcarman/demo", "Counter");
    var b = init("jwcarman/other", "Cache");
    var out = new StringWriter();
    assertThat(run(out, new StringWriter(), "draft", "list")).isZero();
    assertThat(out.toString().lines().toList())
        .containsExactlyInAnyOrder(a + "  jwcarman/demo  Counter", b + "  jwcarman/other  Cache");
  }

  @Test
  void checkPassesACompleteDraft() throws Exception {
    var id = init("jwcarman/demo", "Counter");
    completeDraft(id);
    var out = new StringWriter();
    assertThat(run(out, new StringWriter(), "draft", "check", id)).isZero();
    assertThat(out.toString().strip()).isEqualTo("Draft " + id + " OK");
  }

  @Test
  void checkExitsOneOnProblems() {
    var id = init("jwcarman/demo", "Counter");
    var out = new StringWriter();
    assertThat(run(out, new StringWriter(), "draft", "check", id)).isEqualTo(1);
    assertThat(out.toString()).contains("at least one API stub is required").contains("TODO");
  }

  @Test
  void checkUnknownDraftIsAnError() {
    var err = new StringWriter();
    assertThat(run(new StringWriter(), err, "draft", "check", "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W"))
        .isEqualTo(1);
    assertThat(err.toString().strip()).isEqualTo("No draft nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
  }

  @Test
  void showPrintsTheIssueBody() throws Exception {
    var id = init("jwcarman/demo", "Counter");
    completeDraft(id);
    var out = new StringWriter();
    assertThat(run(out, new StringWriter(), "draft", "show", id)).isZero();
    var dir = tmp.resolve("store/drafts").resolve(id);
    var spec = SpecJson.read(dir.resolve("spec.json"));
    assertThat(out.toString().strip())
        .isEqualTo(
            org.jwcarman.noir.render.SpecRenderer.issueBody(
                    spec, org.jwcarman.noir.render.SpecRenderer.loadStubs(spec, dir))
                .strip());
  }

  @Test
  void showUnknownDraftIsAnError() {
    var err = new StringWriter();
    assertThat(run(new StringWriter(), err, "draft", "show", "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W"))
        .isEqualTo(1);
    assertThat(err.toString()).contains("No draft");
  }
}
