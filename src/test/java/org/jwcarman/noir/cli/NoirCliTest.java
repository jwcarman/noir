package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;

class NoirCliTest {
  @TempDir Path tmp;

  @Test
  void printsVersion() {
    var out = new StringWriter();
    int code =
        NoirCli.execute(
            new NoirHome(tmp),
            new PrintWriter(out),
            new PrintWriter(new StringWriter()),
            "--version");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("noir 0.1.0");
  }

  @Test
  void homeDerivesPaths() {
    var home = new NoirHome(tmp);
    assertThat(home.store()).isEqualTo(tmp.resolve("store"));
    assertThat(home.drafts()).isEqualTo(tmp.resolve("store/drafts"));
    assertThat(home.config()).isEqualTo(tmp.resolve("config.yaml"));
    assertThat(home.lock()).isEqualTo(tmp.resolve("noir.lock"));
  }

  @Test
  void convenienceOperationsHaveNoRealDispositions() {
    var ops =
        new NoirCli.Operations(
            home -> (id, dir) -> null,
            home -> (once, listener) -> {},
            () -> {
              throw new AssertionError("github must not be invoked");
            },
            Set::of);
    assertThatThrownBy(() -> ops.dispositions().apply(new NoirHome(tmp)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("dispositions not configured");
  }
}
