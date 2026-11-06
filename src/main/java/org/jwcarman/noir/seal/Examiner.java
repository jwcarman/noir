package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.jwcarman.noir.agent.AgentPort;
import org.jwcarman.noir.agent.AgentRequest;
import org.jwcarman.noir.commit.SealedFileException;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecProblems;

/** Runs the Examiner agent and collects its holdout tests and reference implementation. */
public final class Examiner {
  private final AgentPort agent;
  private final NoirConfig config;

  public Examiner(AgentPort agent, NoirConfig config) {
    this.agent = agent;
    this.config = config;
  }

  public ExaminerOutput attempt(
      Spec spec,
      List<StubSource> contract,
      List<StubSource> stubs,
      RepoInventory inventory,
      List<String> previousProblems,
      Path attemptDir,
      Path outDir) {
    try {
      deleteRecursively(attemptDir);
      Files.createDirectories(attemptDir.resolve("out/holdout"));
      Files.createDirectories(attemptDir.resolve("out/reference"));
      var prompt = ExaminerPrompt.user(spec, contract, stubs, inventory, previousProblems);
      Files.writeString(attemptDir.resolve("PROMPT.md"), prompt);
      var ex = config.examiner();
      var result =
          agent.run(
              new AgentRequest(
                  config.images().builder(),
                  ExaminerPrompt.system(),
                  prompt,
                  attemptDir,
                  ex.model(),
                  ex.maxTurns(),
                  ex.wallClock(),
                  config.limits()));
      Files.writeString(
          attemptDir.resolve("agent.log"),
          "exitCode="
              + result.exitCode()
              + " error="
              + result.error()
              + " timedOut="
              + result.timedOut()
              + " turns="
              + result.turns()
              + " costUsd="
              + result.costUsd()
              + " model="
              + result.model()
              + "\n\n"
              + result.resultText()
              + "\n");
      // A valid report ends the attempt whatever else the Examiner wrote or how it exited.
      var report =
          SpecProblems.read(
              attemptDir.resolve("out/spec-problems.json"), spec, ApprovalSealer.baseFiles(outDir));
      if (report instanceof SpecProblems.Valid) {
        return new ExaminerOutput(result, null, null, report);
      }
      if (result.error() && !hasJavaFiles(attemptDir.resolve("out"))) {
        throw new ExaminerFailedException(
            "Examiner agent failed: "
                + (result.timedOut() ? "timed out" : result.resultText().strip()));
      }
      var holdout = outDir.resolve("holdout");
      var reference = outDir.resolve("reference");
      replace(attemptDir.resolve("out/holdout"), holdout, "holdout");
      replace(attemptDir.resolve("out/reference"), reference, "reference");
      return new ExaminerOutput(result, holdout, reference, report);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean hasJavaFiles(Path dir) throws IOException {
    try (var paths = Files.walk(dir)) {
      return paths.anyMatch(p -> p.toString().endsWith(".java"));
    }
  }

  /** Replaces {@code target} with the regular {@code .java} files under {@code source}. */
  private static void replace(Path source, Path target, String label) throws IOException {
    deleteRecursively(target);
    Files.createDirectories(target);
    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(source)) {
      for (var p : paths.sorted().toList()) {
        var rel = source.relativize(p);
        if (Files.isSymbolicLink(p)) {
          throw new SealedFileException(
              "Examiner output must not contain symlinks: " + label + "/" + rel);
        }
        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.toString().endsWith(".java")) {
          var dest = target.resolve(rel.toString());
          Files.createDirectories(dest.getParent());
          Files.copy(p, dest);
        }
      }
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
