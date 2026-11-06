package org.jwcarman.noir.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;

/** Prompts for the builder agent (spec §6.3). */
public final class BuilderPrompt {
  static final String FEEDBACK_HEADING = "\n\n# FEEDBACK.md\n\n";
  private static final String SYSTEM = load("/prompts/builder-system.md");
  private static final String WITH_HIDDEN =
      SYSTEM.replace("{{ACCEPTANCE}}", load("/prompts/builder-acceptance-hidden.md").strip());
  private static final String WITHOUT_HIDDEN =
      SYSTEM.replace("{{ACCEPTANCE}}", load("/prompts/builder-acceptance-off.md").strip());

  private BuilderPrompt() {}

  /** The system prompt for an item with hidden tests. */
  public static String system() {
    return system(true);
  }

  /** The system prompt; its Acceptance section names the gate the item really has. */
  public static String system(boolean hiddenTests) {
    return hiddenTests ? WITH_HIDDEN : WITHOUT_HIDDEN;
  }

  /** {@code SPEC.md}, followed by the feedback from the previous attempt when there is one. */
  public static String user(Spec spec, List<StubSource> stubs, Optional<String> feedback) {
    var specMd = SpecRenderer.specMd(spec, stubs);
    return feedback.map(f -> specMd + FEEDBACK_HEADING + f).orElse(specMd);
  }

  private static String load(String resource) {
    try (var in = BuilderPrompt.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
