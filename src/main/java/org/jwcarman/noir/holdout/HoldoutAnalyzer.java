package org.jwcarman.noir.holdout;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;

/** Static checks on a holdout suite (spec §4.3, §5.3 checks 3–4). */
public final class HoldoutAnalyzer {
  private static final Set<String> TEST_ANNOTATIONS =
      Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory");
  private static final List<String> ALLOWED_PREFIXES = List.of("java", "org.junit.jupiter");
  private static final List<String> DENIED_PREFIXES =
      List.of("java.net", "java.nio.channels", "java.util.concurrent", "java.lang.reflect");
  private static final Set<String> CLOCK_TYPES =
      Set.of(
          "Instant",
          "LocalDate",
          "LocalDateTime",
          "LocalTime",
          "ZonedDateTime",
          "OffsetDateTime",
          "OffsetTime",
          "Year",
          "YearMonth",
          "MonthDay",
          "Clock");
  private static final Set<String> THREAD_METHODS =
      Set.of("sleep", "startVirtualThread", "ofPlatform", "ofVirtual", "onSpinWait", "yield");

  private HoldoutAnalyzer() {}

  /**
   * The packages a holdout suite may not use at all, by import or by name. The Examiner prompt
   * lists them, and a test keeps the two in step.
   */
  public static List<String> deniedPackages() {
    return DENIED_PREFIXES;
  }

  /** Analyzes with only {@code stubPackages} (and their subpackages) beyond the JDK and JUnit. */
  public static HoldoutReport analyze(Spec spec, Path holdoutDir, Set<String> stubPackages) {
    return analyze(spec, holdoutDir, stubPackages, Set.of());
  }

  /**
   * Analyzes a holdout suite. Besides {@code java} and {@code org.junit.jupiter}, an import may use
   * a package in {@code subpackageAllowed} (stub and main packages: the package and everything
   * under it) or in {@code exactAllowed} (repository dependency packages: that package only, so a
   * library's internals stay out of reach). Denied packages are never allowed, and {@code
   * org.junit} packages in {@code exactAllowed} are ignored so a shaded jar cannot widen the JUnit
   * platform.
   */
  public static HoldoutReport analyze(
      Spec spec, Path holdoutDir, Set<String> subpackageAllowed, Set<String> exactAllowed) {
    var exact =
        exactAllowed.stream().filter(p -> !under(p, "org.junit")).collect(Collectors.toSet());
    var problems = new ArrayList<String>();
    var counts = new LinkedHashMap<String, Integer>();
    spec.requirements().forEach(r -> counts.put(r.id(), 0));
    var requirementIds = spec.requirements().stream().map(Requirement::id).toList();
    var expectedPackage = ItemId.holdoutPackage(spec.id());
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(org.jwcarman.noir.spec.JavaSyntax.LEVEL));
    var tagsByTest = new LinkedHashMap<String, Set<String>>();
    int totalTests = 0;
    boolean parseFailure = false;

    for (var file : javaFiles(holdoutDir)) {
      var rel =
          holdoutDir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
      String source;
      try {
        source =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString();
      } catch (CharacterCodingException e) {
        problems.add(rel + ": is not valid UTF-8");
        parseFailure = true;
        continue;
      } catch (IOException e) {
        problems.add(rel + ": cannot read: " + e.getMessage());
        parseFailure = true;
        continue;
      }
      var result = parser.parse(source);
      if (!result.isSuccessful() || result.getResult().isEmpty()) {
        problems.add(
            rel
                + ": does not parse: "
                + result.getProblems().stream()
                    .findFirst()
                    .map(p -> p.getMessage())
                    .orElse("unknown error"));
        parseFailure = true;
        continue;
      }
      CompilationUnit cu = result.getResult().get();

      var pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
      if (!pkg.equals(expectedPackage)) {
        problems.add(rel + ": package must be " + expectedPackage);
      }
      for (var imp : cu.getImports()) {
        if (!importAllowed(imp.getNameAsString(), subpackageAllowed, exact)) {
          problems.add(rel + ": import not allowed: " + importText(imp));
        }
      }
      for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
        if (!tagsOf(type).isEmpty()) {
          problems.add(rel + ": @Tag must be on test methods, not the class");
        }
      }
      for (var method : cu.findAll(MethodDeclaration.class)) {
        if (method.getAnnotations().stream()
            .noneMatch(a -> TEST_ANNOTATIONS.contains(simpleName(a)))) {
          continue;
        }
        var where = rel + "#" + method.getNameAsString();
        if (method.isPrivate() || method.isStatic()) {
          problems.add(
              where + ": test method must not be private or static (JUnit will not run it)");
          continue;
        }
        totalTests++;
        var tags = new LinkedHashSet<>(tagsOf(method));
        tagsByTest.put(testKey(pkg, method), tags);
        if (tags.isEmpty()) {
          problems.add(where + ": test has no requirement tag");
        }
        for (var tag : tags) {
          if (requirementIds.contains(tag)) {
            counts.merge(tag, 1, Integer::sum);
          } else {
            problems.add(where + ": tag " + tag + " names no requirement");
          }
        }
      }
      checkDeterminism(cu, rel, problems);
      checkNoRealServer(cu, rel, problems);
    }

    // Coverage is unknowable if any file failed to parse; report only the parse failure(s) in that
    // case.
    if (!parseFailure) {
      counts.forEach((id, n) -> {
        if (n == 0) {
          problems.add(id + " has no holdout tests");
        }
      });
    }
    return new HoldoutReport(problems.stream().distinct().toList(), counts, totalTests, tagsByTest);
  }

  private static String testKey(String pkg, MethodDeclaration method) {
    Node top = method;
    for (var p = method.getParentNode(); p.isPresent(); p = p.get().getParentNode()) {
      if (p.get() instanceof TypeDeclaration<?>) {
        top = p.get();
      }
    }
    var cls = top instanceof TypeDeclaration<?> t ? t.getNameAsString() : "";
    return (pkg.isEmpty() ? "" : pkg + ".") + cls + "#" + method.getNameAsString();
  }

  private static void checkDeterminism(CompilationUnit cu, String rel, List<String> problems) {
    // Static imports: member name -> declaring class simple names; asterisk imports apply to any
    // member.
    var staticMembers = new HashMap<String, List<String>>();
    var staticWildcardClasses = new ArrayList<String>();
    for (var imp : cu.getImports()) {
      if (!imp.isStatic()) {
        continue;
      }
      var name = imp.getNameAsString();
      if (imp.isAsterisk()) {
        staticWildcardClasses.add(name.substring(name.lastIndexOf('.') + 1));
      } else {
        var owner = name.substring(0, name.lastIndexOf('.'));
        staticMembers
            .computeIfAbsent(name.substring(name.lastIndexOf('.') + 1), k -> new ArrayList<>())
            .add(owner.substring(owner.lastIndexOf('.') + 1));
      }
    }
    for (var call : cu.findAll(MethodCallExpr.class)) {
      var name = call.getNameAsString();
      boolean banned;
      if (call.getScope().isPresent()) {
        var scope = call.getScope().get();
        banned =
            bannedCall(lastSegment(scope), name)
                || ((scope instanceof NameExpr || scope instanceof FieldAccessExpr)
                    && deniedName(scope.toString()));
      } else {
        var owners = new ArrayList<>(staticMembers.getOrDefault(name, List.of()));
        owners.addAll(staticWildcardClasses);
        banned = owners.stream().anyMatch(owner -> bannedCall(owner, name));
      }
      if (banned) {
        problems.add(location(call, rel) + ": nondeterministic API not allowed: " + call);
      }
    }
    for (var creation : cu.findAll(ObjectCreationExpr.class)) {
      var type = creation.getType();
      var simple = type.getNameAsString();
      boolean noArgs = creation.getArguments().isEmpty();
      if (simple.equals("Thread")
          || ((simple.equals("Random") || simple.equals("Date")) && noArgs)
          || deniedName(type.getNameWithScope())) {
        problems.add(location(creation, rel) + ": nondeterministic API not allowed: " + creation);
      }
    }
    for (var type : cu.findAll(ClassOrInterfaceType.class)) {
      var parent = type.getParentNode().orElse(null);
      if (parent instanceof ObjectCreationExpr || parent instanceof ClassOrInterfaceType) {
        continue;
      }
      if (deniedName(type.getNameWithScope())) {
        problems.add(
            location(type, rel) + ": nondeterministic API not allowed: " + type.getNameWithScope());
      }
    }
  }

  private static void checkNoRealServer(CompilationUnit cu, String rel, List<String> problems) {
    for (var a : cu.findAll(AnnotationExpr.class)) {
      if (!simpleName(a).equals("SpringBootTest") || !(a instanceof NormalAnnotationExpr n)) {
        continue;
      }
      for (var pair : n.getPairs()) {
        if (!pair.getNameAsString().equals("webEnvironment")) {
          continue;
        }
        var text = pair.getValue().toString();
        var value = text.substring(text.lastIndexOf('.') + 1);
        if (value.equals("RANDOM_PORT") || value.equals("DEFINED_PORT")) {
          problems.add(rel + ": a real server is not allowed: webEnvironment = " + value);
        }
      }
    }
  }

  private static boolean bannedCall(String owner, String name) {
    return (owner.equals("Thread") && THREAD_METHODS.contains(name))
        || (owner.equals("System") && (name.equals("currentTimeMillis") || name.equals("nanoTime")))
        || (owner.equals("Math") && name.equals("random"))
        || (CLOCK_TYPES.contains(owner) && name.equals("now"));
  }

  private static boolean deniedName(String qualifiedName) {
    return DENIED_PREFIXES.stream().anyMatch(p -> under(qualifiedName, p));
  }

  private static String location(Node node, String rel) {
    return node.findAncestor(MethodDeclaration.class)
        .map(m -> rel + "#" + m.getNameAsString())
        .orElse(rel);
  }

  private static boolean importAllowed(
      String name, Set<String> subpackageAllowed, Set<String> exactAllowed) {
    if (DENIED_PREFIXES.stream().anyMatch(p -> under(name, p))) {
      return false;
    }
    return ALLOWED_PREFIXES.stream().anyMatch(p -> under(name, p))
        || subpackageAllowed.stream().anyMatch(p -> under(name, p))
        || exactAllowed.contains(packageOf(name));
  }

  /**
   * The package an import name lives in: the segments before the first one that starts with an
   * upper-case letter, so a nested class or a static member resolves to its package. A wildcard
   * import of a package ({@code a.b.*}, name {@code a.b}) is its own package.
   */
  private static String packageOf(String name) {
    var segments = name.split("\\.");
    var end = 0;
    while (end < segments.length && !Character.isUpperCase(segments[end].charAt(0))) {
      end++;
    }
    return String.join(".", java.util.Arrays.copyOf(segments, end));
  }

  private static boolean under(String name, String prefix) {
    return name.equals(prefix) || name.startsWith(prefix + ".");
  }

  private static String importText(ImportDeclaration imp) {
    return imp.getNameAsString() + (imp.isAsterisk() ? ".*" : "");
  }

  private static List<String> tagsOf(NodeWithAnnotations<?> node) {
    var tags = new ArrayList<String>();
    for (var a : node.getAnnotations()) {
      switch (simpleName(a)) {
        case "Tag" -> annotationValue(a).flatMap(HoldoutAnalyzer::stringValue).ifPresent(tags::add);
        case "Tags" ->
            annotationValue(a)
                .ifPresent(v -> {
                  var elements =
                      v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v);
                  for (var e : elements) {
                    if (e instanceof AnnotationExpr inner && simpleName(inner).equals("Tag")) {
                      annotationValue(inner)
                          .flatMap(HoldoutAnalyzer::stringValue)
                          .ifPresent(tags::add);
                    }
                  }
                });
        default -> {}
      }
    }
    return tags;
  }

  private static Optional<Expression> annotationValue(AnnotationExpr a) {
    if (a instanceof SingleMemberAnnotationExpr s) {
      return Optional.of(s.getMemberValue());
    }
    if (a instanceof NormalAnnotationExpr n) {
      return n.getPairs().stream()
          .filter(p -> p.getNameAsString().equals("value"))
          .map(p -> p.getValue())
          .findFirst();
    }
    return Optional.empty();
  }

  private static Optional<String> stringValue(Expression e) {
    return e instanceof StringLiteralExpr s ? Optional.of(s.asString()) : Optional.empty();
  }

  private static String simpleName(AnnotationExpr a) {
    return a.getName().getIdentifier();
  }

  private static String lastSegment(Expression scope) {
    var text = scope.toString();
    return text.substring(text.lastIndexOf('.') + 1);
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
