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

  public static String user(Spec spec, List<StubSource> stubs, List<String> previousProblems) {
    var sb = new StringBuilder(SpecRenderer.specBody(spec, stubs));
    sb.append("\n## Holdout package\n\n`").append(ItemId.holdoutPackage(spec.id())).append("`\n");
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
