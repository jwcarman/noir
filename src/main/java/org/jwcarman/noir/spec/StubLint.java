package org.jwcarman.noir.spec;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jwcarman.noir.render.StubSource;

/** Mechanical checks on API stub sources: path shape, parseability, package, uniqueness. */
public final class StubLint {
  private static final String SRC = "src/main/java/";

  private StubLint() {}

  public static List<String> lint(List<StubSource> stubs) {
    var problems = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (var stub : stubs) {
      var path = stub.path();
      if (!seen.add(path)) {
        problems.add("duplicate stub path " + path);
        continue;
      }
      if (!isWellFormed(path)) {
        problems.add(
            "stub " + path + " must be a relative path under src/main/java/ ending in .java");
        continue;
      }
      checkSource(stub, problems);
    }
    return problems;
  }

  /**
   * True for a relative {@code src/main/java/**.java} path with no empty, {@code .} or {@code ..}
   * segments.
   */
  public static boolean isWellFormed(String path) {
    if (path == null || !path.startsWith(SRC) || !path.endsWith(".java") || path.contains("\\")) {
      return false;
    }
    for (var segment : path.split("/")) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  private static void checkSource(StubSource stub, List<String> problems) {
    var path = stub.path();
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    var result = parser.parse(stub.content());
    if (!result.isSuccessful() || result.getResult().isEmpty()) {
      problems.add("stub " + path + " does not parse as Java");
      return;
    }
    var declared =
        result
            .getResult()
            .get()
            .getPackageDeclaration()
            .map(NodeWithName::getNameAsString)
            .orElse("");
    var dir = path.substring(SRC.length(), path.lastIndexOf('/') + 1);
    var expected = dir.isEmpty() ? "" : dir.substring(0, dir.length() - 1).replace('/', '.');
    if (!declared.equals(expected)) {
      problems.add(
          "stub "
              + path
              + " declares package '"
              + declared
              + "' but its directory implies '"
              + expected
              + "'");
    }
  }
}
