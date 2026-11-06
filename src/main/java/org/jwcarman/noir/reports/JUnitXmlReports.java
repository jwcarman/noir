package org.jwcarman.noir.reports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jwcarman.noir.feedback.TestOutcome;
import org.w3c.dom.Element;

/** Reads JUnit legacy XML reports (as written by the JUnit console launcher). */
public final class JUnitXmlReports {
  private JUnitXmlReports() {}

  public static List<TestOutcome> parse(Path reportsDir) {
    var outcomes = new ArrayList<TestOutcome>();
    for (var file : files(reportsDir)) {
      outcomes.addAll(parseFile(file));
    }
    return outcomes;
  }

  /**
   * Like {@link #parse}, but a report that is not a regular file or cannot be parsed is left out
   * rather than failing the whole parse: for reports written where builder code ran, its tests then
   * count as not run.
   */
  public static List<TestOutcome> parseLenient(Path reportsDir) {
    var outcomes = new ArrayList<TestOutcome>();
    for (var file : files(reportsDir)) {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try {
        outcomes.addAll(parseFile(file));
      } catch (IllegalArgumentException e) {
        // unreadable: its tests did not run, as far as noir can tell
      }
    }
    return outcomes;
  }

  private static List<Path> files(Path reportsDir) {
    if (!Files.isDirectory(reportsDir)) {
      return List.of();
    }
    try (var s = Files.list(reportsDir)) {
      return s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<TestOutcome> parseFile(Path file) {
    var outcomes = new ArrayList<TestOutcome>();
    var cases = Xml.parse(file).getElementsByTagName("testcase");
    for (int i = 0; i < cases.getLength(); i++) {
      var tc = (Element) cases.item(i);
      var id = tc.getAttribute("classname") + "#" + tc.getAttribute("name");
      if (tc.getElementsByTagName("skipped").getLength() > 0) {
        outcomes.add(new TestOutcome(id, Set.of(), TestOutcome.Result.SKIPPED, null));
        continue;
      }
      var problem = firstChild(tc, "failure");
      if (problem == null) {
        problem = firstChild(tc, "error");
      }
      if (problem == null) {
        outcomes.add(new TestOutcome(id, Set.of(), TestOutcome.Result.PASSED, null));
      } else {
        var message = problem.getAttribute("message");
        var result =
            message.contains("timed out after")
                ? TestOutcome.Result.TIMED_OUT
                : TestOutcome.Result.FAILED;
        outcomes.add(new TestOutcome(id, Set.of(), result, message));
      }
    }
    return outcomes;
  }

  private static Element firstChild(Element parent, String tag) {
    var list = parent.getElementsByTagName(tag);
    return list.getLength() == 0 ? null : (Element) list.item(0);
  }
}
