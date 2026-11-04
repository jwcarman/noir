package org.jwcarman.noir.holdout;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;

/** Static checks on a holdout suite (spec §4.3, §5.3 checks 3–4). */
public final class HoldoutAnalyzer {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory");
    private static final List<String> ALLOWED_PREFIXES = List.of("java", "org.junit.jupiter");
    private static final List<String> DENIED_PREFIXES =
            List.of("java.net", "java.nio.channels", "java.util.concurrent", "java.lang.reflect");
    private static final Set<String> CLOCK_TYPES =
            Set.of("Instant", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime", "OffsetDateTime", "Clock");

    private HoldoutAnalyzer() {}

    public static HoldoutReport analyze(Spec spec, Path holdoutDir, Set<String> stubPackages) {
        var problems = new ArrayList<String>();
        var counts = new LinkedHashMap<String, Integer>();
        spec.requirements().forEach(r -> counts.put(r.id(), 0));
        var requirementIds = spec.requirements().stream().map(Requirement::id).toList();
        var expectedPackage = ItemId.holdoutPackage(spec.id());
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        int totalTests = 0;
        boolean parseFailure = false;

        for (var file : javaFiles(holdoutDir)) {
            var rel = holdoutDir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
            CompilationUnit cu;
            try {
                var result = parser.parse(Files.readString(file));
                if (!result.isSuccessful() || result.getResult().isEmpty()) {
                    problems.add(rel + ": does not parse: " + result.getProblems().stream()
                            .findFirst().map(p -> p.getMessage()).orElse("unknown error"));
                    parseFailure = true;
                    continue;
                }
                cu = result.getResult().get();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            var pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (!pkg.equals(expectedPackage)) {
                problems.add(rel + ": package must be " + expectedPackage);
            }
            for (var imp : cu.getImports()) {
                if (!importAllowed(imp.getNameAsString(), stubPackages)) {
                    problems.add(rel + ": import not allowed: " + importText(imp));
                }
            }
            for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!tagsOf(type).isEmpty()) {
                    problems.add(rel + ": @Tag must be on test methods, not the class");
                }
            }
            for (var method : cu.findAll(MethodDeclaration.class)) {
                if (method.getAnnotations().stream().noneMatch(a -> TEST_ANNOTATIONS.contains(simpleName(a)))) {
                    continue;
                }
                totalTests++;
                var where = rel + "#" + method.getNameAsString();
                var tags = tagsOf(method);
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
        }

        // Coverage is unknowable if any file failed to parse; report only the parse failure(s) in that case.
        if (!parseFailure) {
            counts.forEach((id, n) -> {
                if (n == 0) {
                    problems.add(id + " has no holdout tests");
                }
            });
        }
        return new HoldoutReport(problems, counts, totalTests);
    }

    private static void checkDeterminism(CompilationUnit cu, String rel, List<String> problems) {
        for (var call : cu.findAll(MethodCallExpr.class)) {
            var scope = call.getScope().map(HoldoutAnalyzer::lastSegment).orElse("");
            var name = call.getNameAsString();
            boolean banned = (scope.equals("Thread") && name.equals("sleep"))
                    || (scope.equals("System") && (name.equals("currentTimeMillis") || name.equals("nanoTime")))
                    || (scope.equals("Math") && name.equals("random"))
                    || (CLOCK_TYPES.contains(scope) && name.equals("now"));
            if (banned) {
                problems.add(location(call, rel) + ": nondeterministic API not allowed: " + call);
            }
        }
        for (var creation : cu.findAll(ObjectCreationExpr.class)) {
            var type = creation.getType().getNameAsString();
            if (type.equals("Thread") || (type.equals("Random") && creation.getArguments().isEmpty())) {
                problems.add(location(creation, rel) + ": nondeterministic API not allowed: " + creation);
            }
        }
    }

    private static String location(Node node, String rel) {
        return node.findAncestor(MethodDeclaration.class)
                .map(m -> rel + "#" + m.getNameAsString())
                .orElse(rel);
    }

    private static boolean importAllowed(String name, Set<String> stubPackages) {
        if (DENIED_PREFIXES.stream().anyMatch(p -> under(name, p))) {
            return false;
        }
        return ALLOWED_PREFIXES.stream().anyMatch(p -> under(name, p))
                || stubPackages.stream().anyMatch(p -> under(name, p));
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
                case "Tags" -> annotationValue(a).ifPresent(v -> {
                    var elements = v instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(v);
                    for (var e : elements) {
                        if (e instanceof AnnotationExpr inner && simpleName(inner).equals("Tag")) {
                            annotationValue(inner).flatMap(HoldoutAnalyzer::stringValue).ifPresent(tags::add);
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
            return s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
