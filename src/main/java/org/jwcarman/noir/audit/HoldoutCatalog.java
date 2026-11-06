package org.jwcarman.noir.audit;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jwcarman.noir.holdout.HoldoutAnalyzer;
import org.jwcarman.noir.spec.Spec;

/** The hidden holdout tests of a sealed item, with their requirement tags and source text. */
public final class HoldoutCatalog {
  private HoldoutCatalog() {}

  /** One holdout test method; {@code id} is {@code SimpleClassName#method}. */
  public record HoldoutTest(String id, Set<String> requirements, String source) {
    public HoldoutTest {
      requirements = Set.copyOf(requirements);
    }
  }

  /** A holdout file could not be parsed, so the catalog would be incomplete. */
  public static final class UnreadableHoldoutException extends RuntimeException {
    public UnreadableHoldoutException(String message) {
      super(message);
    }
  }

  /** Tests in file-then-source order. Tags come from {@link HoldoutAnalyzer}. */
  public static List<HoldoutTest> load(Spec spec, Path holdoutDir) {
    // Imports are irrelevant to tags, so no stub packages are needed here.
    var tags = HoldoutAnalyzer.analyze(spec, holdoutDir, Set.of()).tagsByTest();
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(org.jwcarman.noir.spec.JavaSyntax.LEVEL));
    var tests = new ArrayList<HoldoutTest>();
    for (var file : javaFiles(holdoutDir)) {
      String text;
      try {
        text = Files.readString(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      var result = parser.parse(text);
      if (!result.isSuccessful() || result.getResult().isEmpty()) {
        throw new UnreadableHoldoutException(
            holdoutDir.relativize(file).toString().replace('\\', '/')
                + ": does not parse: "
                + result.getProblems().stream()
                    .findFirst()
                    .map(p -> p.getMessage())
                    .orElse("unknown error"));
      }
      var cu = result.getResult().get();
      var pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
      var lines = text.split("\r?\n", -1);
      for (var method : cu.findAll(MethodDeclaration.class)) {
        var top = topLevel(method);
        var key = (pkg.isEmpty() ? "" : pkg + ".") + top + "#" + method.getNameAsString();
        var methodTags = tags.get(key);
        if (methodTags == null) {
          continue;
        }
        tests.add(
            new HoldoutTest(
                top + "#" + method.getNameAsString(), methodTags, source(method, lines)));
      }
    }
    return List.copyOf(tests);
  }

  private static String topLevel(MethodDeclaration method) {
    String cls = "";
    for (var p = method.getParentNode(); p.isPresent(); p = p.get().getParentNode()) {
      Node n = p.get();
      if (n instanceof TypeDeclaration<?> t) {
        cls = t.getNameAsString();
      }
    }
    return cls;
  }

  /** The method's own lines (Javadoc included), with the common indentation removed. */
  private static String source(MethodDeclaration method, String[] lines) {
    var range = method.getRange().orElseThrow();
    int begin = range.begin.line;
    int end = range.end.line;
    if (method.getComment().isPresent()) {
      begin = Math.min(begin, method.getComment().get().getRange().orElseThrow().begin.line);
    }
    var slice = new ArrayList<String>(List.of(lines).subList(begin - 1, end));
    int indent =
        slice.stream()
            .filter(l -> !l.isBlank())
            .mapToInt(l -> l.length() - l.stripLeading().length())
            .min()
            .orElse(0);
    return slice.stream()
        .map(l -> l.length() >= indent ? l.substring(indent) : l.strip())
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }

  private static List<Path> javaFiles(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
