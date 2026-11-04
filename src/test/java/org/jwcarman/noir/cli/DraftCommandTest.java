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
}
