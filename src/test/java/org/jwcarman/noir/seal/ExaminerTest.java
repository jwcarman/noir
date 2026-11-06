package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.commit.SealedFileException;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.spec.Specs;

class ExaminerTest {
  @TempDir Path tmp;

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void copiesOnlyJavaFiles() {
    var agent =
        new ScriptedAgent((n, work) -> {
          write(work.resolve("out/holdout/noir/holdout/x/T.java"), "class T {}");
          write(work.resolve("out/holdout/notes.txt"), "ignore me");
          write(work.resolve("out/reference/src/main/java/a/A.java"), "class A {}");
          write(work.resolve("in/stray.java"), "class S {}");
        });
    var draft = tmp.resolve("draft");
    var out =
        new Examiner(agent, NoirConfig.defaults())
            .attempt(
                Specs.valid(), List.of(), List.of(), List.of(), tmp.resolve("attempt-1"), draft);
    assertThat(draft.resolve("holdout/noir/holdout/x/T.java")).hasContent("class T {}");
    assertThat(draft.resolve("holdout/notes.txt")).doesNotExist();
    assertThat(draft.resolve("reference/src/main/java/a/A.java")).hasContent("class A {}");
    assertThat(draft.resolve("in")).doesNotExist();
    assertThat(out.holdoutDir()).isEqualTo(draft.resolve("holdout"));
    assertThat(out.agent().model()).isEqualTo("scripted");
  }

  @Test
  void rejectsSymlinks() {
    var agent =
        new ScriptedAgent((n, work) -> {
          write(work.resolve("out/holdout/Real.java"), "class R {}");
          try {
            Files.createSymbolicLink(
                work.resolve("out/holdout/Link.java"), work.resolve("out/holdout/Real.java"));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    assertThatThrownBy(() ->
            new Examiner(agent, NoirConfig.defaults())
                .attempt(
                    Specs.valid(),
                    List.of(),
                    List.of(),
                    List.of(),
                    tmp.resolve("a"),
                    tmp.resolve("d")))
        .isInstanceOf(SealedFileException.class)
        .hasMessageContaining("holdout/Link.java");
  }

  @Test
  void replacesPreviousAttemptOutput() {
    var agent =
        new ScriptedAgent(
            (n, work) -> write(work.resolve("out/holdout/T" + n + ".java"), "class T" + n + " {}"));
    var examiner = new Examiner(agent, NoirConfig.defaults());
    var draft = tmp.resolve("draft");
    examiner.attempt(
        Specs.valid(), List.of(), List.of(), List.of(), tmp.resolve("attempt-1"), draft);
    examiner.attempt(
        Specs.valid(), List.of(), List.of(), List.of("x"), tmp.resolve("attempt-2"), draft);
    assertThat(draft.resolve("holdout/T1.java")).doesNotExist();
    assertThat(draft.resolve("holdout/T2.java")).exists();
    assertThat(draft.resolve("reference")).isDirectory();
  }

  @Test
  void staleAttemptOutputIsCleared() {
    var attemptDir = tmp.resolve("attempt-1");
    write(attemptDir.resolve("out/holdout/Stale.java"), "class Stale {}");
    var agent =
        new ScriptedAgent((n, work) -> write(work.resolve("out/holdout/Fresh.java"), "class F {}"));
    var draft = tmp.resolve("draft");
    new Examiner(agent, NoirConfig.defaults())
        .attempt(Specs.valid(), List.of(), List.of(), List.of(), attemptDir, draft);
    assertThat(draft.resolve("holdout/Stale.java")).doesNotExist();
    assertThat(draft.resolve("holdout/Fresh.java")).exists();
  }

  @Test
  void passesExaminerSettingsToAgentAndRecordsPrompt() {
    var agent = new ScriptedAgent((n, work) -> {});
    var attemptDir = tmp.resolve("attempt-1");
    new Examiner(agent, NoirConfig.defaults())
        .attempt(Specs.valid(), List.of(), List.of(), List.of(), attemptDir, tmp.resolve("d"));
    var req = agent.requests().getFirst();
    assertThat(req.image()).isEqualTo("noir-builder:0.1.0");
    assertThat(req.maxTurns()).isEqualTo(100);
    assertThat(req.wallClock()).isEqualTo(Duration.ofMinutes(30));
    assertThat(req.model()).isNull();
    assertThat(req.workDir()).isEqualTo(attemptDir);
    assertThat(req.systemPrompt()).isEqualTo(ExaminerPrompt.system());
    assertThat(attemptDir.resolve("PROMPT.md")).hasContent(req.prompt());
  }
}
