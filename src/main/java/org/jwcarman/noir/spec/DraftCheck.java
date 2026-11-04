package org.jwcarman.noir.spec;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Pre-seal checks on a draft directory: spec lint, stub files, and leftover placeholders. */
public final class DraftCheck {
  private static final String SRC = "src/main/java/";
  private static final String NO_BASE_SHA = "baseSha must be a 40-character hex commit SHA";

  private DraftCheck() {}

  public static List<String> check(Path draftDir) {
    var specFile = draftDir.resolve("spec.json");
    if (!Files.isRegularFile(specFile)) {
      return List.of("spec.json not found in " + draftDir);
    }
    Spec spec;
    try {
      spec = SpecJson.read(specFile);
    } catch (RuntimeException e) {
      return List.of("spec.json is unreadable: " + e.getMessage());
    }
    var problems = new ArrayList<String>();
    SpecLint.lint(spec).stream().filter(p -> !p.equals(NO_BASE_SHA)).forEach(problems::add);
    checkStubs(spec, draftDir.resolve("stubs"), problems);
    checkPlaceholders(spec, problems);
    return problems;
  }

  private static void checkStubs(Spec spec, Path stubs, List<String> problems) {
    var listed = new HashSet<String>(spec.apiStubs());
    for (var path : spec.apiStubs()) {
      // Malformed entries are already reported by SpecLint; never touch the disk for them.
      if (isWellFormed(path)) {
        checkStub(stubs, path, problems);
      }
    }
    if (Files.isDirectory(stubs, LinkOption.NOFOLLOW_LINKS)) {
      unlisted(stubs, listed)
          .forEach(p -> problems.add("stub " + p + " is not listed in apiStubs"));
    }
  }

  private static boolean isWellFormed(String path) {
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

  private static void checkStub(Path stubs, String path, List<String> problems) {
    var file = stubs.resolve(path);
    if (Files.isSymbolicLink(file) || hasSymlinkParent(stubs, file)) {
      problems.add("stub " + path + " must not be a symbolic link");
      return;
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      problems.add("stub " + path + " is missing from stubs/");
      return;
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      problems.add("stub " + path + " is not a regular file");
      return;
    }
    String source;
    try {
      source = Files.readString(file);
    } catch (IOException e) {
      problems.add("stub " + path + " cannot be read: " + e.getMessage());
      return;
    }
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    var result = parser.parse(source);
    if (!result.isSuccessful() || result.getResult().isEmpty()) {
      problems.add("stub " + path + " does not parse as Java");
      return;
    }
    var declared = packageOf(result.getResult().get());
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

  private static String packageOf(CompilationUnit unit) {
    return unit.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("");
  }

  private static boolean hasSymlinkParent(Path stubs, Path file) {
    for (var p = file.getParent(); p != null && p.startsWith(stubs); p = p.getParent()) {
      if (Files.isSymbolicLink(p)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> unlisted(Path stubs, Set<String> listed) {
    try (Stream<Path> walk = Files.walk(stubs)) {
      return walk.filter(p -> p.getFileName().toString().endsWith(".java"))
          .filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
          .map(p -> stubs.relativize(p).toString().replace('\\', '/'))
          .filter(rel -> !listed.contains(rel))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void checkPlaceholders(Spec spec, List<String> problems) {
    for (var r : spec.requirements()) {
      todo(r.statement(), r.id() + " statement", problems);
      for (int j = 0; j < r.examples().size(); j++) {
        var e = r.examples().get(j);
        var prefix = r.id() + " example " + (j + 1) + " ";
        todo(e.given(), prefix + "'given'", problems);
        todo(e.when(), prefix + "'when'", problems);
        todo(e.then(), prefix + "'then'", problems);
      }
    }
  }

  private static void todo(String value, String field, List<String> problems) {
    if (value != null && value.contains("TODO")) {
      problems.add(field + " still contains a TODO placeholder");
    }
  }
}
