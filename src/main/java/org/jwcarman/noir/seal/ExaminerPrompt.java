package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Spec;

/** Prompts for the Examiner agent (spec §5.2). */
public final class ExaminerPrompt {
  private static final String SYSTEM = load("/prompts/examiner-system.md");

  private ExaminerPrompt() {}

  public static String system() {
    return SYSTEM;
  }

  /**
   * The Examiner's input: the spec with the issue's {@code contract} view (fragments under their
   * {@code (modify)} headings) and, for a spec that modifies files, each modify target's complete
   * spliced file from {@code stubs}.
   */
  public static String user(
      Spec spec, List<StubSource> contract, List<StubSource> stubs, List<String> previousProblems) {
    var sb = new StringBuilder(SpecRenderer.specBody(spec, contract));
    sb.append("\n## Holdout package\n\n`").append(ItemId.holdoutPackage(spec.id())).append("`\n");
    if (!spec.modifies().isEmpty()) {
      sb.append("\n## Current source\n");
      for (var stub : stubs) {
        if (spec.modifies().contains(stub.path())) {
          var content = stub.content().endsWith("\n") ? stub.content() : stub.content() + "\n";
          var fence = SpecRenderer.fenceFor(content);
          sb.append("\n### ")
              .append(stub.path())
              .append("\n\n")
              .append(fence)
              .append("java\n")
              .append(content)
              .append(fence)
              .append('\n');
        }
      }
    }
    if (!previousProblems.isEmpty()) {
      sb.append("\n## Problems with your previous attempt\n\n");
      for (var problem : previousProblems) {
        var lines = problem.split("\n", -1);
        sb.append("- ").append(lines[0]).append('\n');
        if (lines.length > 1) {
          sb.append("  ```\n");
          for (int i = 1; i < lines.length; i++) {
            sb.append("  ").append(lines[i]).append('\n');
          }
          sb.append("  ```\n");
        }
      }
    }
    return sb.toString();
  }

  private static String load(String resource) {
    try (var in = ExaminerPrompt.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
