package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretsTest {
  static final Set<String> SECRETS = Set.of("sk-ant-1");

  @Test
  void walksTheCauseChainRedactsAndKeepsOneLine() {
    var e =
        new IllegalStateException(
            "outer\nline", new UncheckedIOException("mid", new IOException("key sk-ant-1 bad")));
    assertThat(Secrets.describe(e, SECRETS)).isEqualTo("outer line: mid: key [redacted] bad");
  }

  @Test
  void skipsNullAndRepeatedMessages() {
    var cause = new IOException("disk full");
    var e = new UncheckedIOException(cause); // message is the cause's toString
    assertThat(Secrets.describe(e, SECRETS)).isEqualTo("java.io.IOException: disk full");
    assertThat(Secrets.describe(new RuntimeException(new NullPointerException()), SECRETS))
        .isEqualTo("java.lang.NullPointerException");
    assertThat(Secrets.describe(new NullPointerException(), SECRETS))
        .isEqualTo("NullPointerException");
    assertThat(Secrets.describe(new InfraFailure(new IOException("x")), SECRETS)).isEqualTo("x");
  }

  @Test
  void blankSecretsAreIgnored() {
    assertThat(Secrets.redact("a  b", Set.of(" ", ""))).isEqualTo("a b");
  }

  @Test
  void maskKeepsFormatting() {
    var text = "line one sk-ant-1\n\n- two  spaces\n" + "x".repeat(600);
    assertThat(Secrets.mask(text, SECRETS))
        .isEqualTo("line one [redacted]\n\n- two  spaces\n" + "x".repeat(600));
    assertThat(Secrets.mask(null, SECRETS)).isEmpty();
  }
}
