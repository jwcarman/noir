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
   * spliced file from {@code stubs} and each listed test's base content from {@code inventory};
   * then what the repository offers hidden tests: its test libraries, Spring guidance when the
   * repository uses Spring, and its public API.
   */
  public static String user(
      Spec spec,
      List<StubSource> contract,
      List<StubSource> stubs,
      RepoInventory inventory,
      List<String> previousProblems) {
    var sb = new StringBuilder(SpecRenderer.specBody(spec, contract));
    sb.append("\n## Holdout package\n\n`").append(ItemId.holdoutPackage(spec.id())).append("`\n");
    if (!spec.modifies().isEmpty() || !inventory.testsToUpdate().isEmpty()) {
      sb.append("\n## Current source\n");
      for (var stub : stubs) {
        if (spec.modifies().contains(stub.path())) {
          appendSource(sb, stub.path(), stub.content());
        }
      }
      inventory
          .testsToUpdate()
          .forEach((path, content) -> appendSource(sb, path + " (test to update)", content));
    }
    sb.append("\n## Test libraries\n\n");
    if (inventory.testLibraries().isEmpty()) {
      sb.append("JUnit and the JDK only\n");
    } else {
      for (var library : inventory.testLibraries()) {
        sb.append("- ").append(library).append('\n');
      }
    }
    if (usesSpring(inventory)) {
      sb.append("\n## Spring\n\n");
      inventory
          .applicationClass()
          .ifPresent(application ->
              sb.append("The application class is `")
                  .append(application)
                  .append("`. Name it in `@SpringBootTest(classes = ")
                  .append(application)
                  .append(".class)` or `@ContextConfiguration(classes = ")
                  .append(application)
                  .append(".class)`.\n\n"));
      sb.append(
          "Prefer test slices (`@WebMvcTest`, `@DataJpaTest` and the like) over a full context."
              + " Never start a real server: use `MockMvc`, not `webEnvironment = RANDOM_PORT` or"
              + " `DEFINED_PORT`. Replace collaborators with `@MockitoBean`.\n");
    }
    if (!inventory.repositoryApi().isBlank()) {
      sb.append("\n## Repository API\n\n").append(inventory.repositoryApi().strip()).append('\n');
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

  private static void appendSource(StringBuilder sb, String heading, String source) {
    var content = source.endsWith("\n") ? source : source + "\n";
    var fence = SpecRenderer.fenceFor(content);
    sb.append("\n### ")
        .append(heading)
        .append("\n\n")
        .append(fence)
        .append("java\n")
        .append(content)
        .append(fence)
        .append('\n');
  }

  private static boolean usesSpring(RepoInventory inventory) {
    return inventory.applicationClass().isPresent()
        || inventory.testLibraries().stream()
            .anyMatch(l ->
                l.startsWith("org.springframework:spring-test:")
                    || l.startsWith("org.springframework.boot:spring-boot-test:"));
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
