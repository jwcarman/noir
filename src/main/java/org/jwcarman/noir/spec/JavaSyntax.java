package org.jwcarman.noir.spec;

import com.github.javaparser.ParserConfiguration;

/**
 * The Java language level noir parses source at. It is the newest release noir accepts ({@code
 * maven.compiler.release} 17 to 25), so any repository's source parses; javac in the containers
 * still enforces the repository's real release.
 */
public final class JavaSyntax {

  /** The language level for every JavaParser configuration in noir. */
  public static final ParserConfiguration.LanguageLevel LEVEL =
      ParserConfiguration.LanguageLevel.JAVA_25;

  private JavaSyntax() {}
}
