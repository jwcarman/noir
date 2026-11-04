package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.factory.StateChange;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.store.InstanceLock;

class RunCommandTest {
  static final String A = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A";
  static final String SECRET = "sk-ant-secret-value";

  @TempDir Path tmp;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();
  final List<Boolean> runs = new ArrayList<>();

  int run(RunOperation op, String... args) {
    var ops =
        new NoirCli.Operations(
            home -> (id, dir) -> null, home -> op, FakeGitHub::new, () -> Set.of(SECRET));
    return NoirCli.execute(
        new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), ops, args);
  }

  @Test
  void onceRunsASinglePass() {
    int code = run((once, listener) -> runs.add(once), "run", "--once");
    assertThat(code).isZero();
    assertThat(runs).containsExactly(true);
  }

  @Test
  void withoutOnceRunsForever() {
    int code = run((once, listener) -> runs.add(once), "run");
    assertThat(code).isZero();
    assertThat(runs).containsExactly(false);
  }

  @Test
  void printsOneLinePerTransitionAndErrorsToStderr() {
    int code =
        run(
            (once, listener) -> {
              listener.transition(new StateChange(A, State.BUILDING, State.MEASURING));
              listener.transition(new StateChange(A, State.MEASURING, State.GRADING));
              listener.error(A, "docker daemon not reachable");
              listener.error(null, "GitHub listing failed");
            },
            "run",
            "--once");
    assertThat(code).isZero();
    assertThat(out.toString())
        .isEqualTo(A + " BUILDING → MEASURING\n" + A + " MEASURING → GRADING\n");
    assertThat(err.toString())
        .isEqualTo(A + ": docker daemon not reachable\nGitHub listing failed\n");
  }

  @Test
  void heldLockExitsTwoWithoutRunning() {
    try (var held = InstanceLock.tryAcquire(new NoirHome(tmp).lock()).orElseThrow()) {
      int code = run((once, listener) -> runs.add(once), "run", "--once");
      assertThat(code).isEqualTo(2);
    }
    assertThat(runs).isEmpty();
    assertThat(err.toString()).isEqualTo("Another noir run is already active\n");
  }

  @Test
  void lockIsReleasedAfterTheRun() {
    run((once, listener) -> runs.add(once), "run", "--once");
    try (var again = InstanceLock.tryAcquire(new NoirHome(tmp).lock()).orElseThrow()) {
      assertThat(again).isNotNull();
    }
  }

  @Test
  void infraErrorsAreOneLine() {
    int code =
        run(
            (once, listener) -> {
              throw new DockerException("Cannot connect to the Docker daemon");
            },
            "run",
            "--once");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("Cannot connect to the Docker daemon\n");
  }

  @Test
  void missingCredentialsAreOneLine() {
    int code =
        run(
            (once, listener) -> {
              throw new IllegalStateException("Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN");
            },
            "run");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("Set ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN\n");
  }

  @Test
  void multiLineMessagesAndSecretsInCausesBecomeOneRedactedLine() {
    int code =
        run(
            (once, listener) -> {
              throw new IllegalStateException(
                  "docker build failed\nstep 3 of 7",
                  new RuntimeException("token " + SECRET + " rejected"));
            },
            "run");
    assertThat(code).isEqualTo(2);
    var text = err.toString();
    assertThat(text).doesNotContain(SECRET).endsWith("\n");
    assertThat(text.lines()).hasSize(1);
    assertThat(text).contains("docker build failed step 3 of 7").contains("rejected");
  }

  @Test
  void anyRuntimeExceptionIsOneLineWithoutAStackTrace() {
    int code =
        run(
            (once, listener) -> {
              throw new NullPointerException();
            },
            "run",
            "--once");
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).isEqualTo("NullPointerException\n");
  }
}
