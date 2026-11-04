package org.jwcarman.noir.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessCommandRunnerTest {
  final CommandRunner runner = new ProcessCommandRunner();

  @Test
  void failedDrainThrowsInsteadOfReturningEmptyOutput() {
    var broken =
        new java.io.InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("pipe broke");
          }
        };
    var drained = ProcessCommandRunner.drain(broken);
    assertThatThrownBy(() -> ProcessCommandRunner.get(drained, Duration.ofSeconds(5)))
        .isInstanceOf(java.io.UncheckedIOException.class)
        .hasMessageContaining("pipe broke");
  }

  @Test
  void drainTimeoutThrowsInsteadOfReturningEmptyOutput() {
    var never = new java.util.concurrent.CompletableFuture<String>();
    assertThatThrownBy(() -> ProcessCommandRunner.get(never, Duration.ofMillis(20)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not finish");
  }

  @Test
  void drainErrorThrowsInsteadOfReturningEmptyOutput() {
    var failed =
        java.util.concurrent.CompletableFuture.<String>failedFuture(new OutOfMemoryError("oom"));
    assertThatThrownBy(() -> ProcessCommandRunner.get(failed, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("oom");
  }

  @Test
  void capturesStdoutStderrAndExit() {
    var r = runner.run(Command.of("sh", "-c", "echo out; echo err >&2; exit 3"));
    assertThat(r.exitCode()).isEqualTo(3);
    assertThat(r.stdout()).isEqualTo("out\n");
    assertThat(r.stderr()).isEqualTo("err\n");
    assertThat(r.ok()).isFalse();
  }

  @Test
  void feedsStdin() {
    assertThat(runner.run(Command.of("cat").withStdin("hello")).stdout()).isEqualTo("hello");
  }

  @Test
  void addsEnvAndCwd(@TempDir Path dir) throws Exception {
    var r =
        runner.run(
            Command.of("sh", "-c", "echo $NOIR_X; pwd").withEnv(Map.of("NOIR_X", "42")).inDir(dir));
    assertThat(r.stdout()).startsWith("42\n").contains(dir.toRealPath().toString());
  }

  @Test
  void timesOutAndKillsChildren() {
    var start = System.nanoTime();
    var r =
        runner.run(
            Command.of("sh", "-c", "sleep 30 & sleep 30").withTimeout(Duration.ofMillis(300)));
    assertThat(r.timedOut()).isTrue();
    assertThat(r.ok()).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
  }

  @Test
  void missingToolIsNamed() {
    assertThatThrownBy(() -> runner.run(Command.of("noir-no-such-tool")))
        .isInstanceOf(ToolMissingException.class)
        .hasMessage("noir-no-such-tool is not installed or not on PATH");
  }

  @Test
  void missingDirectoryIsNotReportedAsMissingTool(@TempDir Path dir) {
    assertThatThrownBy(() -> runner.run(Command.of("sh", "-c", "true").inDir(dir.resolve("nope"))))
        .isNotInstanceOf(ToolMissingException.class);
  }

  @Test
  void tailCombinesStreams() {
    assertThat(new CommandResult(1, "a\nb\n", "c\n", false).tail(2)).isEqualTo("b\nc");
  }

  @Test
  void largeOutputDoesNotDeadlock() {
    var r =
        runner.run(Command.of("sh", "-c", "yes x | head -c 2000000; yes y | head -c 2000000 >&2"));
    assertThat(r.stdout()).hasSize(2_000_000);
    assertThat(r.stderr()).hasSize(2_000_000);
  }
}
