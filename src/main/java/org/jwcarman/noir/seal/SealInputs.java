package org.jwcarman.noir.seal;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.noir.spec.Spec;

/** Everything a seal check needs. {@code targetClasses} are the reference's top-level types. */
public record SealInputs(
    Spec spec,
    Path bundle,
    Path stubsDir,
    Path holdoutDir,
    Path referenceDir,
    List<String> targetClasses) {

  public SealInputs {
    targetClasses = List.copyOf(targetClasses);
  }

  public static SealInputs of(
      Spec spec, Path bundle, Path stubsDir, Path holdoutDir, Path referenceDir) {
    return new SealInputs(
        spec, bundle, stubsDir, holdoutDir, referenceDir, topLevelTypes(referenceDir));
  }

  static List<String> topLevelTypes(Path dir) {
    var parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    var names = new ArrayList<String>();
    try (var files = Files.walk(dir)) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        var cu = parser.parse(Files.readString(file)).getResult();
        if (cu.isEmpty()) {
          continue;
        }
        var pkg = cu.get().getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        cu.get().getTypes().forEach(t -> names.add(pkg + t.getNameAsString()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }
}
