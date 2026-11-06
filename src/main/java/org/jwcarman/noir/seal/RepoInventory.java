package org.jwcarman.noir.seal;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;

/**
 * What the repository offers hidden tests at the base commit, computed once per seal before the
 * Examiner runs: its libraries ({@code group:artifact:version}, sorted, JUnit's own left out), the
 * packages in those libraries' jars, the packages under {@code src/main/java}, the one top-level
 * {@code @SpringBootApplication} class (if exactly one), the Repository API text, and the base
 * content of each test file the spec lists under "Tests to update" (path to content, spec order).
 */
public record RepoInventory(
    List<String> testLibraries,
    Set<String> dependencyPackages,
    Set<String> mainPackages,
    Optional<String> applicationClass,
    String repositoryApi,
    Map<String, String> testsToUpdate) {

  /** The Repository API section's size limit, in characters. */
  public static final int REPOSITORY_API_CAP = 60_000;

  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final Set<String> SPRING_BOOT_APPLICATION =
      Set.of(
          "SpringBootApplication", "org.springframework.boot.autoconfigure.SpringBootApplication");

  public RepoInventory {
    testLibraries = List.copyOf(testLibraries);
    dependencyPackages = Collections.unmodifiableSortedSet(new TreeSet<>(dependencyPackages));
    mainPackages = Collections.unmodifiableSortedSet(new TreeSet<>(mainPackages));
    testsToUpdate = Collections.unmodifiableMap(new LinkedHashMap<>(testsToUpdate));
  }

  /**
   * The inventory from the seal check's listing and the base commit's {@code src/main/java/**.java}
   * ({@code mainSources}, path to content). {@code contract} is the spec's API stubs as the issue
   * shows them; types they name come first in the Repository API. {@code testsToUpdate} is the
   * listed test files' base content, path to content.
   */
  public static RepoInventory of(
      RepoInventoryFiles files,
      Map<String, String> mainSources,
      Map<String, String> testsToUpdate,
      Spec spec,
      List<StubSource> contract) {
    var mainPackages = new TreeSet<String>();
    var applications = new ArrayList<String>();
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(org.jwcarman.noir.spec.JavaSyntax.LEVEL));
    for (var source : new TreeMap<>(mainSources).values()) {
      var m = PACKAGE.matcher(source);
      var pkg = m.find() ? m.group(1) : "";
      if (!pkg.isEmpty()) {
        mainPackages.add(pkg);
      }
      var parsed = parser.parse(source);
      if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
        continue;
      }
      for (TypeDeclaration<?> type : parsed.getResult().get().getTypes()) {
        if (type.getAnnotations().stream()
            .anyMatch(a -> SPRING_BOOT_APPLICATION.contains(a.getNameAsString()))) {
          var name = type.getNameAsString();
          applications.add(pkg.isEmpty() ? name : pkg + "." + name);
        }
      }
    }
    return new RepoInventory(
        List.copyOf(new TreeSet<>(files.libraries())),
        new TreeSet<>(files.packages()),
        mainPackages,
        applications.size() == 1 ? Optional.of(applications.getFirst()) : Optional.empty(),
        RepositoryApi.render(mainSources, spec, contract, REPOSITORY_API_CAP),
        testsToUpdate);
  }
}
