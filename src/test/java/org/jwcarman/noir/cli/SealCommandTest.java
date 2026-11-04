package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.seal.SealResult;

class SealCommandTest {
  @TempDir Path tmp;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();
  Path seenRepoDir;

  int run(SealOperation op, String... args) {
    return NoirCli.execute(
        new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), home -> op, args);
  }

  @Test
  void sealPrintsResult() {
    int code =
        run(
            (id, dir) -> {
              seenRepoDir = dir;
              return new SealResult.Sealed(id, "jwcarman/demo", 12, "abcdef0123456789ffff");
            },
            "seal",
            "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W",
            "--repo-dir",
            tmp.toString());
    assertThat(code).isZero();
    assertThat(out.toString())
        .isEqualTo(
            "Sealed nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W as jwcarman/demo#12 (commitment abcdef012345)\n");
    assertThat(seenRepoDir).isEqualTo(tmp.toAbsolutePath().normalize());
  }

  @Test
  void repoDirDefaultsToCurrentDirectory() {
    run(
        (id, dir) -> {
          seenRepoDir = dir;
          return new SealResult.Rejected(List.of("x"));
        },
        "seal",
        "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
    assertThat(seenRepoDir).isEqualTo(Path.of("").toAbsolutePath().normalize());
  }

  @Test
  void sealRejectedListsProblems() {
    int code =
        run(
            (id, dir) -> new SealResult.Rejected(List.of("a", "b")),
            "seal",
            "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
    assertThat(code).isEqualTo(1);
    assertThat(out.toString()).isEqualTo("Not sealed:\n- a\n- b\n");
  }

  @Test
  void infraErrorsAreOneLine() {
    int code =
        run(
            (id, dir) -> {
              throw new GitHubException("GitHub CLI is not authenticated; run: gh auth login");
            },
            "seal",
            "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("GitHub CLI is not authenticated; run: gh auth login\n");
  }

  @Test
  void examinerFailureIsOneLine() {
    int code =
        run(
            (id, dir) -> {
              throw new org.jwcarman.noir.seal.ExaminerFailedException(
                  "Examiner agent failed: Invalid API key");
            },
            "seal",
            "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("Examiner agent failed: Invalid API key\n");
  }

  @Test
  void unexpectedIoErrorsAreOneLine() {
    int code =
        run(
            (id, dir) -> {
              throw new java.io.UncheckedIOException(
                  "Cannot move a to b", new java.io.IOException("disk full"));
            },
            "seal",
            "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("Cannot move a to b\n");
  }

  @Test
  void missingCredentialsAreOneLine() {
    int code =
        run(
            (id, dir) -> {
              throw new IllegalStateException("Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN");
            },
            "seal",
            "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN\n");
  }
}
