package org.jwcarman.noir.spec;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Splices an issue fragment (a compilation unit holding only new or changed methods and
 * constructors of one top-level type) into the file at the base commit.
 *
 * <p>A fragment member whose signature (name plus parameter types as written) matches a base member
 * is <em>changed</em>: it keeps the base body and annotations and takes the fragment's Javadoc. Any
 * other fragment member is <em>added</em> before the type's closing brace. Missing imports are
 * added. Every other byte of the base file is left as it is.
 */
public final class StubSplicer {
  private static final String DEFAULT_INDENT = "  ";
  private static final String UOE = "throw new UnsupportedOperationException();";

  private StubSplicer() {}

  /** The spliced file and the display signatures (for example {@code parse(String, char)}). */
  public record Spliced(String content, List<String> added, List<String> changed) {
    public Spliced {
      added = List.copyOf(added);
      changed = List.copyOf(changed);
    }
  }

  private record Edit(int start, int end, String text) {}

  private record Source(String text, int[] lineStarts) {
    static Source of(String text) {
      var starts = new ArrayList<Integer>();
      starts.add(0);
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '\r') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
            i++;
          }
          starts.add(i + 1);
        } else if (c == '\n') {
          starts.add(i + 1);
        }
      }
      return new Source(text, starts.stream().mapToInt(Integer::intValue).toArray());
    }

    int offset(Position p) {
      return lineStarts[p.line - 1] + p.column - 1;
    }

    int begin(Node node) {
      return offset(node.getRange().orElseThrow().begin);
    }

    /** Exclusive end offset. */
    int end(Node node) {
      return offset(node.getRange().orElseThrow().end) + 1;
    }

    String text(Node node) {
      return text.substring(begin(node), end(node));
    }

    /** The whitespace before {@code node} on its line, or empty when other text precedes it. */
    Optional<String> indentOf(Node node) {
      int begin = begin(node);
      int lineStart = lineStarts[node.getRange().orElseThrow().begin.line - 1];
      var prefix = text.substring(lineStart, begin);
      return prefix.isBlank() ? Optional.of(prefix) : Optional.empty();
    }
  }

  public static Spliced splice(String path, String baseSource, String fragment) {
    var baseUnit =
        parse(baseSource)
            .orElseThrow(() ->
                fail(
                    path,
                    "the file at the base commit does not parse",
                    "Fix the file on the base branch first, or add a new file instead."));
    var fragmentUnit = parseFragment(path, fragment);
    var base = Source.of(baseSource);
    var frag = Source.of(fragment);
    var sep = baseSource.contains("\r\n") ? "\r\n" : "\n";

    checkPackage(path, baseUnit, fragmentUnit);
    var fragmentType = singleType(path, fragmentUnit);
    var target = targetType(path, baseUnit, fragmentType);
    var members = fragmentMembers(path, fragmentType);

    var baseMembers = new HashMap<String, CallableDeclaration<?>>();
    for (var member : target.getMembers()) {
      if (member instanceof CallableDeclaration<?> callable) {
        baseMembers.putIfAbsent(key(callable), callable);
      }
    }

    var edits = new ArrayList<Edit>();
    var added = new ArrayList<String>();
    var changed = new ArrayList<String>();
    var memberIndent =
        target.getMembers().stream()
            .findFirst()
            .flatMap(base::indentOf)
            .filter(s -> !s.isEmpty())
            .orElse(DEFAULT_INDENT);
    var appended = new StringBuilder();

    for (var member : members) {
      var baseMember = baseMembers.get(key(member));
      if (baseMember != null) {
        checkSameDeclaration(path, baseMember, member);
        changed.add(display(member));
        var fragmentDoc = member.getJavadocComment();
        if (fragmentDoc.isPresent()) {
          var indent = base.indentOf(baseMember).orElse(memberIndent);
          var doc = reindent(frag, fragmentDoc.get(), indent, sep);
          var baseDoc = baseMember.getJavadocComment();
          if (baseDoc.isPresent()) {
            edits.add(new Edit(base.begin(baseDoc.get()), base.end(baseDoc.get()), doc));
          } else {
            int at = base.begin(baseMember);
            edits.add(new Edit(at, at, doc + sep + indent));
          }
        }
      } else {
        added.add(display(member));
        appended.append(sep).append(sep).append(memberIndent);
        member
            .getJavadocComment()
            .ifPresent(doc ->
                appended
                    .append(reindent(frag, doc, memberIndent, sep))
                    .append(sep)
                    .append(memberIndent));
        appended.append(reindent(frag, member, memberIndent, sep));
      }
    }

    if (!appended.isEmpty()) {
      edits.add(appendEdit(base, target, appended, sep));
    }
    importEdit(base, baseUnit, frag, fragmentUnit, sep).ifPresent(edits::add);

    edits.sort(Comparator.comparingInt(Edit::start).reversed());
    var out = new StringBuilder(baseSource);
    for (var edit : edits) {
      out.replace(edit.start(), edit.end(), edit.text());
    }
    return new Spliced(out.toString(), added, changed);
  }

  // ---- parsing and validation ----

  private static JavaParser parser() {
    return new JavaParser(
        new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setTabSize(1));
  }

  private static Optional<CompilationUnit> parse(String source) {
    var result = parser().parse(source);
    return result.isSuccessful() ? result.getResult() : Optional.empty();
  }

  private static CompilationUnit parseFragment(String path, String fragment) {
    var result = parser().parse(fragment);
    if (result.isSuccessful() && result.getResult().isPresent()) {
      return result.getResult().get();
    }
    var where =
        result.getProblems().stream()
            .findFirst()
            .flatMap(p -> p.getLocation())
            .flatMap(l -> l.getBegin().getRange())
            .map(r -> " (line " + r.begin.line + ", column " + r.begin.column + ")")
            .orElse("");
    throw fail(path, "the fragment does not parse" + where, "Fix the Java syntax in the fragment.");
  }

  private static void checkPackage(String path, CompilationUnit base, CompilationUnit fragment) {
    var basePackage = packageOf(base);
    var fragmentPackage = packageOf(fragment);
    if (!basePackage.equals(fragmentPackage)) {
      throw fail(
          path,
          "the fragment declares package '"
              + fragmentPackage
              + "' but the file declares '"
              + basePackage
              + "'",
          "Use the file's package in the fragment.");
    }
  }

  private static String packageOf(CompilationUnit unit) {
    return unit.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("");
  }

  private static TypeDeclaration<?> singleType(String path, CompilationUnit fragment) {
    if (fragment.getTypes().size() != 1) {
      throw fail(
          path,
          "the fragment must declare exactly one top-level type, but it declares "
              + fragment.getTypes().size(),
          "Keep only the type you are changing in the fragment.");
    }
    return fragment.getType(0);
  }

  private static TypeDeclaration<?> targetType(
      String path, CompilationUnit base, TypeDeclaration<?> fragmentType) {
    var name = fragmentType.getNameAsString();
    var target =
        base.getTypes().stream()
            .filter(t -> t.getNameAsString().equals(name))
            .findFirst()
            .orElseThrow(() ->
                fail(
                    path,
                    "the fragment's type "
                        + name
                        + " is not a top-level type in the file at the base commit",
                    "Name an existing top-level type, or add a new file instead."));
    var baseKind = kind(target);
    var fragmentKind = kind(fragmentType);
    if (!baseKind.equals(fragmentKind)) {
      throw fail(
          path,
          name
              + " is "
              + article(baseKind)
              + " in the file but "
              + article(fragmentKind)
              + " in the fragment",
          "Declare it as " + article(baseKind) + " in the fragment.");
    }
    var baseModifiers = modifiers(target.getModifiers());
    var fragmentModifiers = modifiers(fragmentType.getModifiers());
    if (!baseModifiers.equals(fragmentModifiers)) {
      throw fail(
          path,
          name
              + " has modifiers '"
              + describe(baseModifiers)
              + "' in the file but '"
              + describe(fragmentModifiers)
              + "' in the fragment",
          "Copy the type's modifiers from the file.");
    }
    return target;
  }

  private static String kind(TypeDeclaration<?> type) {
    return switch (type) {
      case ClassOrInterfaceDeclaration c -> c.isInterface() ? "interface" : "class";
      case EnumDeclaration e -> "enum";
      case RecordDeclaration r -> "record";
      case AnnotationDeclaration a -> "annotation type";
      default -> "type";
    };
  }

  private static String article(String kind) {
    return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind;
  }

  private static List<CallableDeclaration<?>> fragmentMembers(
      String path, TypeDeclaration<?> fragmentType) {
    if (fragmentType instanceof EnumDeclaration e && !e.getEntries().isEmpty()) {
      throw notAllowed(path, "an enum constant");
    }
    var members = new ArrayList<CallableDeclaration<?>>();
    var seen = new HashSet<String>();
    for (BodyDeclaration<?> member : fragmentType.getMembers()) {
      if (!(member instanceof MethodDeclaration) && !(member instanceof ConstructorDeclaration)) {
        throw notAllowed(path, describeMember(member));
      }
      var callable = (CallableDeclaration<?>) member;
      if (!isUoeBody(callable)) {
        throw fail(
            path,
            "the body of " + display(callable) + " in the fragment is not exactly '" + UOE + "'",
            "Replace the body with that single statement.");
      }
      if (!seen.add(key(callable))) {
        throw fail(
            path,
            "the fragment declares " + display(callable) + " twice",
            "Keep one declaration of each signature.");
      }
      members.add(callable);
    }
    return members;
  }

  private static String describeMember(BodyDeclaration<?> member) {
    return switch (member) {
      case FieldDeclaration f -> "a field";
      case InitializerDeclaration i -> "an initializer";
      case TypeDeclaration<?> t -> "a nested type";
      default -> "a member that is not a method or constructor";
    };
  }

  private static SpliceException notAllowed(String path, String what) {
    return fail(
        path,
        "the fragment's type may contain only methods and constructors, but it has " + what,
        "Remove it from the fragment; fields, initializers and nested types cannot be changed"
            + " this way.");
  }

  private static boolean isUoeBody(CallableDeclaration<?> callable) {
    Optional<BlockStmt> body =
        switch (callable) {
          case MethodDeclaration m -> m.getBody();
          case ConstructorDeclaration c -> Optional.of(c.getBody());
          default -> Optional.empty();
        };
    if (body.isEmpty() || body.get().getStatements().size() != 1) {
      return false;
    }
    if (!(body.get().getStatement(0) instanceof ThrowStmt thrown)
        || !(thrown.getExpression() instanceof ObjectCreationExpr created)) {
      return false;
    }
    return created.getScope().isEmpty()
        && created.getTypeArguments().isEmpty()
        && created.getAnonymousClassBody().isEmpty()
        && created.getArguments().isEmpty()
        && created.getType().toString().equals("UnsupportedOperationException");
  }

  private static void checkSameDeclaration(
      String path, CallableDeclaration<?> base, CallableDeclaration<?> fragment) {
    var signature = display(fragment);
    var hint = "Make it match the file, or give the new behavior a new signature.";
    if (base instanceof MethodDeclaration baseMethod
        && fragment instanceof MethodDeclaration fragmentMethod
        && !strip(baseMethod.getType()).equals(strip(fragmentMethod.getType()))) {
      throw fail(
          path,
          signature
              + " has return type '"
              + fragmentMethod.getType()
              + "' in the fragment but '"
              + baseMethod.getType()
              + "' in the file",
          hint);
    }
    var baseModifiers = modifiers(base.getModifiers());
    var fragmentModifiers = modifiers(fragment.getModifiers());
    if (!baseModifiers.equals(fragmentModifiers)) {
      throw fail(
          path,
          signature
              + " has modifiers '"
              + describe(fragmentModifiers)
              + "' in the fragment but '"
              + describe(baseModifiers)
              + "' in the file",
          hint);
    }
    if (!stripAll(base.getTypeParameters()).equals(stripAll(fragment.getTypeParameters()))) {
      throw fail(
          path,
          signature
              + " has type parameters '"
              + fragment.getTypeParameters()
              + "' in the fragment but '"
              + base.getTypeParameters()
              + "' in the file",
          hint);
    }
    if (!stripAll(base.getThrownExceptions()).equals(stripAll(fragment.getThrownExceptions()))) {
      throw fail(
          path,
          signature
              + " has throws clause '"
              + fragment.getThrownExceptions()
              + "' in the fragment but '"
              + base.getThrownExceptions()
              + "' in the file",
          hint);
    }
  }

  private static Set<Modifier.Keyword> modifiers(List<Modifier> modifiers) {
    var set = EnumSet.noneOf(Modifier.Keyword.class);
    modifiers.forEach(m -> set.add(m.getKeyword()));
    return set;
  }

  private static String describe(Set<Modifier.Keyword> modifiers) {
    return modifiers.isEmpty()
        ? "none"
        : modifiers.stream().map(Modifier.Keyword::asString).collect(Collectors.joining(" "));
  }

  // ---- signatures ----

  private static String key(CallableDeclaration<?> callable) {
    var kind = callable instanceof ConstructorDeclaration ? "new " : "";
    return kind
        + callable.getNameAsString()
        + "("
        + callable.getParameters().stream()
            .map(p -> strip(p.getType()) + (p.isVarArgs() ? "..." : ""))
            .collect(Collectors.joining(","))
        + ")";
  }

  private static String display(CallableDeclaration<?> callable) {
    return callable.getNameAsString()
        + "("
        + callable.getParameters().stream()
            .map(p -> p.getType().toString() + (p.isVarArgs() ? "..." : ""))
            .collect(Collectors.joining(", "))
        + ")";
  }

  private static String strip(Node node) {
    return node.toString().replaceAll("\\s+", "");
  }

  private static List<String> stripAll(List<? extends Node> nodes) {
    return nodes.stream().map(StubSplicer::strip).toList();
  }

  // ---- text edits ----

  /**
   * The source text of {@code node} in the fragment, with its continuation lines moved from the
   * fragment's indentation to {@code indent} and its line breaks changed to {@code sep}.
   */
  private static String reindent(Source frag, Node node, String indent, String sep) {
    var lines = frag.text(node).split("\r\n|\r|\n", -1);
    var fromIndent = frag.indentOf(node).orElse("").length();
    var out = new StringBuilder(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      var line = lines[i];
      int strip = 0;
      while (strip < fromIndent
          && strip < line.length()
          && Character.isWhitespace(line.charAt(strip))) {
        strip++;
      }
      var rest = line.substring(strip);
      out.append(sep).append(rest.isEmpty() ? "" : indent + rest);
    }
    return out.toString();
  }

  /** Inserts the added members after the last non-blank text before the type's closing brace. */
  private static Edit appendEdit(
      Source base, TypeDeclaration<?> target, StringBuilder appended, String sep) {
    int brace = base.end(target) - 1;
    var text = base.text();
    int at = brace;
    while (at > 0 && Character.isWhitespace(text.charAt(at - 1))) {
      at--;
    }
    var between = text.substring(at, brace);
    if (!between.contains("\n") && !between.contains("\r")) {
      appended.append(sep);
    }
    return new Edit(at, at, appended.toString());
  }

  private static Optional<Edit> importEdit(
      Source base,
      CompilationUnit baseUnit,
      Source frag,
      CompilationUnit fragmentUnit,
      String sep) {
    var present = new HashSet<String>();
    baseUnit.getImports().forEach(i -> present.add(importKey(i)));
    var missing = new ArrayList<String>();
    for (var imported : fragmentUnit.getImports()) {
      if (present.add(importKey(imported))) {
        missing.add(frag.text(imported));
      }
    }
    if (missing.isEmpty()) {
      return Optional.empty();
    }
    var imports = baseUnit.getImports();
    if (!imports.isEmpty()) {
      int at = imports.stream().mapToInt(base::end).max().orElseThrow();
      return Optional.of(new Edit(at, at, sep + String.join(sep, missing)));
    }
    var block = String.join(sep, missing);
    return Optional.of(
        baseUnit
            .getPackageDeclaration()
            .map(p -> new Edit(base.end(p), base.end(p), sep + sep + block))
            .orElse(new Edit(0, 0, block + sep + sep)));
  }

  private static String importKey(ImportDeclaration i) {
    return (i.isStatic() ? "static " : "") + i.getNameAsString() + (i.isAsterisk() ? ".*" : "");
  }

  private static SpliceException fail(String path, String problem, String hint) {
    return new SpliceException(path + ": " + problem + ". " + hint);
  }
}
