package org.jwcarman.noir.seal;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.Spec;

/**
 * The Repository API section of the Examiner's prompt: the declarations and Javadoc of the
 * repository's main sources, without method bodies, field initializers, private members or
 * non-Javadoc comments. One {@code ### <path>} heading and {@code java} fence per file. Files
 * declaring a type the spec names come first, then the rest, each group by path. A file that does
 * not fit the cap is left out whole, never cut, and a final line counts the files left out.
 */
public final class RepositoryApi {
  private static final Pattern WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  private RepositoryApi() {}

  /**
   * Renders {@code sources} (path to content) in at most {@code cap} characters. A type is named by
   * the spec when its simple name is a whole word of an API stub in {@code contract} or of a
   * requirement's statement or examples, or of a constraint. Unparseable files are left out too.
   */
  public static String render(
      Map<String, String> sources, Spec spec, List<StubSource> contract, int cap) {
    var words = specWords(spec, contract);
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    var named = new ArrayList<String>();
    var rest = new ArrayList<String>();
    int unparsed = 0;
    for (var entry : new TreeMap<>(sources).entrySet()) {
      var parsed = parser.parse(entry.getValue());
      if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
        unparsed++;
        continue;
      }
      var cu = parsed.getResult().get();
      strip(cu);
      var isNamed =
          cu.findAll(TypeDeclaration.class).stream()
              .anyMatch(t -> words.contains(t.getNameAsString()));
      var code = cu.toString();
      var text =
          "### "
              + entry.getKey()
              + "\n```java\n"
              + code
              + (code.endsWith("\n") ? "" : "\n")
              + "```\n\n";
      (isNamed ? named : rest).add(text);
    }
    var ordered = new ArrayList<>(named);
    ordered.addAll(rest);

    var all = fill(ordered, cap);
    if (all.omitted() == 0 && unparsed == 0) {
      return all.text();
    }
    // Room for the final line, sized for the most files that could be left out.
    var reserve = trailer(ordered.size() + unparsed).length();
    var kept = fill(ordered, cap - reserve);
    return kept.text() + trailer(kept.omitted() + unparsed);
  }

  private record Filled(String text, int omitted) {}

  private static Filled fill(List<String> blocks, int budget) {
    var sb = new StringBuilder();
    int omitted = 0;
    for (var b : blocks) {
      if (sb.length() + b.length() <= budget) {
        sb.append(b);
      } else {
        omitted++;
      }
    }
    return new Filled(sb.toString(), omitted);
  }

  private static String trailer(int omitted) {
    return "(… " + omitted + " more files omitted)\n";
  }

  private static Set<String> specWords(Spec spec, List<StubSource> contract) {
    var text = new StringBuilder();
    contract.forEach(s -> text.append(s.content()).append('\n'));
    for (var r : spec.requirements()) {
      text.append(r.statement()).append('\n');
      for (var e : r.examples()) {
        text.append(e.given())
            .append('\n')
            .append(e.when())
            .append('\n')
            .append(e.then())
            .append('\n');
      }
    }
    spec.constraints().forEach(c -> text.append(c).append('\n'));
    var words = new HashSet<String>();
    var m = WORD.matcher(text);
    while (m.find()) {
      words.add(m.group());
    }
    return words;
  }

  /** Reduces {@code cu} to its non-private declarations and Javadoc. */
  private static void strip(CompilationUnit cu) {
    for (Comment c : cu.getAllContainedComments()) {
      if (!(c instanceof JavadocComment)) {
        c.remove();
      }
    }
    cu.getComment().filter(c -> !(c instanceof JavadocComment)).ifPresent(c -> cu.removeComment());
    cu.getTypes().forEach(RepositoryApi::stripType);
  }

  private static void stripType(TypeDeclaration<?> type) {
    for (BodyDeclaration<?> member : new ArrayList<>(type.getMembers())) {
      if (member instanceof InitializerDeclaration
          || (member instanceof NodeWithModifiers<?> m
              && m.hasModifier(Modifier.Keyword.PRIVATE))) {
        member.remove();
      } else if (member instanceof MethodDeclaration method) {
        method.removeBody();
      } else if (member instanceof ConstructorDeclaration constructor) {
        constructor.setBody(new BlockStmt());
      } else if (member instanceof CompactConstructorDeclaration constructor) {
        constructor.setBody(new BlockStmt());
      } else if (member instanceof FieldDeclaration field) {
        field.getVariables().forEach(VariableDeclarator::removeInitializer);
      } else if (member instanceof TypeDeclaration<?> nested) {
        stripType(nested);
      }
    }
    if (type instanceof EnumDeclaration e) {
      e.getEntries().forEach(constant -> constant.getClassBody().clear());
    }
  }
}
