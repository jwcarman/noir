package org.jwcarman.noir.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jwcarman.noir.render.StubSource;

/**
 * The spec as written in a GitHub issue: a title plus the strict markdown body rendered by {@code
 * SpecRenderer.issueBody}. Parsing is line-based and collects every problem it can.
 */
public record IssueSpec(
    String title,
    String intent,
    List<Requirement> requirements,
    List<String> constraints,
    QualityBar qualityBar,
    List<StubSource> stubs,
    List<String> modifies,
    List<String> testsToUpdate) {

  private static final String PLACEHOLDER_ID = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W";
  private static final String PLACEHOLDER_REPO = "owner/repo";
  private static final String PLACEHOLDER_SHA = "0123456789abcdef0123456789abcdef01234567";

  private static final List<String> SECTIONS =
      List.of(
          "Intent",
          "API contract",
          "Requirements",
          "Tests to update",
          "Constraints",
          "Quality bar");
  private static final String OPTIONAL_SECTION = "Tests to update";
  private static final String MODIFY_SUFFIX = " (modify)";
  private static final Pattern TEST_ENTRY = Pattern.compile("^- `([^`]+)`$");
  private static final Pattern FENCE_OPEN = Pattern.compile("^(`{3,})java$");
  private static final Pattern REQ_HEADING = Pattern.compile("^### (R\\d+)$");
  private static final String EXAMPLE_PREFIX = "- **Given**";
  private static final Pattern EXAMPLE =
      Pattern.compile("^- \\*\\*Given\\*\\*(.*?)\\*\\*when\\*\\*(.*?)\\*\\*then\\*\\*(.*)$");
  private static final String EXAMPLE_MSG = "example must be - **Given** … **when** … **then** …";
  private static final List<Pattern> BAR_PATTERNS =
      List.of(
          Pattern.compile("^- Line coverage ≥ (\\d{1,3})%$"),
          Pattern.compile("^- Mutation score ≥ (\\d{1,3})%$"),
          Pattern.compile("^- Max CRAP score per method ≤ (\\d{1,6})$"));
  private static final List<String> BAR_MSGS =
      List.of(
          "expected - Line coverage ≥ <n>%",
          "expected - Mutation score ≥ <n>%", "expected - Max CRAP score per method ≤ <n>");

  public IssueSpec {
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    stubs = stubs == null ? List.of() : List.copyOf(stubs);
    modifies = modifies == null ? List.of() : List.copyOf(modifies);
    testsToUpdate = testsToUpdate == null ? List.of() : List.copyOf(testsToUpdate);
  }

  /**
   * The one normalizer, for parsing and hashing: CRLF to LF, trailing spaces and tabs stripped from
   * every line outside the {@code ## API contract} code fences (lines inside a fence are kept
   * verbatim; its closing fence line is stripped too), and trailing whitespace on the whole body
   * trimmed to one final newline.
   */
  public static String normalize(String body) {
    var lines = body.replace("\r\n", "\n").split("\n", -1);
    var out = new StringBuilder();
    String fence = null;
    boolean inContract = false;
    for (var raw : lines) {
      var line = raw;
      if (fence != null) {
        if (stripLine(raw).equals(fence)) {
          line = fence;
          fence = null;
        }
      } else {
        line = stripLine(raw);
        if (line.startsWith("## ")) {
          inContract = line.substring(3).strip().equals("API contract");
        } else if (inContract) {
          var m = FENCE_OPEN.matcher(line);
          if (m.matches()) {
            fence = m.group(1);
          }
        }
      }
      out.append(line).append('\n');
    }
    return out.toString().stripTrailing() + "\n";
  }

  private static String stripLine(String line) {
    int end = line.length();
    while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
      end--;
    }
    return line.substring(0, end);
  }

  /**
   * Builds the full {@link Spec} (apiStubs = stub paths in order; schemaVersion 2 when it modifies
   * files or lists tests).
   */
  public Spec toSpec(String id, String repo, String baseSha) {
    boolean v2 = !modifies.isEmpty() || !testsToUpdate.isEmpty();
    return new Spec(
        v2 ? 2 : 1,
        id,
        repo,
        baseSha,
        title,
        intent,
        requirements,
        constraints,
        qualityBar,
        stubs.stream().map(StubSource::path).toList(),
        modifies,
        testsToUpdate);
  }

  /**
   * Content problems: the spec lint rules (minus identity) plus stub and TODO checks, worded as the
   * issue body is (these messages are posted publicly): never {@code spec.json} field names.
   */
  public static List<String> lint(IssueSpec spec) {
    var problems = new ArrayList<String>();
    for (var p :
        SpecLint.lintContent(spec.toSpec(PLACEHOLDER_ID, PLACEHOLDER_REPO, PLACEHOLDER_SHA))) {
      if (p.startsWith("qualityBar")) {
        continue; // checked below in the body's wording
      }
      problems.add(p.startsWith("apiStubs entry ") ? "API stub path " + p.substring(15) : p);
    }
    lintQualityBar(spec.qualityBar(), problems);
    problems.addAll(
        StubLint.lint(spec.stubs().stream().filter(s -> StubLint.isWellFormed(s.path())).toList()));
    todo(spec.title(), "title", problems);
    todo(spec.intent(), "intent", problems);
    for (int i = 0; i < spec.constraints().size(); i++) {
      todo(spec.constraints().get(i), "constraint " + (i + 1), problems);
    }
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
    for (var s : spec.stubs()) {
      todo(s.path(), "stub path " + s.path(), problems);
      todo(s.content(), "stub " + s.path(), problems);
    }
    return problems;
  }

  private static void lintQualityBar(QualityBar bar, List<String> problems) {
    if (bar == null) {
      problems.add("## Quality bar is required");
      return;
    }
    if (!(bar.lineCoverage() > 0 && bar.lineCoverage() <= 1)) {
      problems.add("Line coverage must be between 1% and 100%");
    }
    if (!(bar.mutationScore() > 0 && bar.mutationScore() <= 1)) {
      problems.add("Mutation score must be between 1% and 100%");
    }
    if (bar.maxCrap() < 1) {
      problems.add("Max CRAP score per method must be at least 1");
    }
  }

  private static void todo(String value, String field, List<String> problems) {
    if (value != null && value.contains("TODO")) {
      problems.add(field + " still contains a TODO placeholder");
    }
  }

  /** Parses an issue body strictly; throws {@link IssueSpecException} listing every problem. */
  public static IssueSpec parse(String title, String body) {
    return new Parser(dropFooter(normalize(body).split("\n", -1)), title).run();
  }

  private static List<String> dropFooter(String[] raw) {
    var lines = new ArrayList<>(List.of(raw));
    while (!lines.isEmpty() && lines.getLast().isBlank()) {
      lines.removeLast();
    }
    if (!lines.isEmpty() && lines.getLast().startsWith("_noir")) {
      int j = lines.size() - 2;
      while (j >= 0 && lines.get(j).isBlank()) {
        j--;
      }
      if (j >= 0 && lines.get(j).equals("---")) {
        return new ArrayList<>(lines.subList(0, j));
      }
    }
    return lines;
  }

  /** One heading found in the body: its name and zero-based line index. */
  private record Heading(String name, int line) {}

  private static final class Parser {
    private final List<String> lines;
    private final String title;
    private final List<String> problems = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private final List<String> modifies = new ArrayList<>();

    Parser(List<String> lines, String title) {
      this.lines = lines;
      this.title = title;
    }

    private void problem(int zeroBasedLine, String message) {
      problems.add("line " + (zeroBasedLine + 1) + ": " + message);
    }

    IssueSpec run() {
      if (!scanHeadings()) {
        throw new IssueSpecException(problems);
      }
      checkStructure();
      String intent = "";
      List<StubSource> stubs = List.of();
      List<Requirement> reqs = List.of();
      List<String> constraints = List.of();
      List<String> tests = List.of();
      QualityBar bar = null;
      for (int i = 0; i < headings.size(); i++) {
        var h = headings.get(i);
        int from = h.line() + 1;
        int to = i + 1 < headings.size() ? headings.get(i + 1).line() : lines.size();
        if (headings.stream().limit(i).anyMatch(p -> p.name().equals(h.name()))) {
          continue;
        }
        switch (h.name()) {
          case "Intent" -> intent = String.join("\n", lines.subList(from, to)).strip();
          case "API contract" -> stubs = parseStubs(from, to);
          case "Requirements" -> reqs = parseRequirements(from, to);
          case "Tests to update" -> tests = parseTests(from, to);
          case "Constraints" -> constraints = parseConstraints(h.line(), from, to);
          case "Quality bar" -> bar = parseBar(h.line(), from, to);
          default -> {}
        }
      }
      if (!problems.isEmpty()) {
        throw new IssueSpecException(problems);
      }
      return new IssueSpec(title, intent, reqs, constraints, bar, stubs, modifies, tests);
    }

    /** Finds {@code ## } headings outside code fences. Returns false after an unclosed fence. */
    private boolean scanHeadings() {
      String fence = null;
      int fenceLine = -1;
      for (int i = 0; i < lines.size(); i++) {
        var line = lines.get(i);
        if (fence != null) {
          if (line.equals(fence)) {
            fence = null;
          }
          continue;
        }
        if (line.startsWith("## ")) {
          headings.add(new Heading(line.substring(3).strip(), i));
        } else if (!headings.isEmpty() && headings.getLast().name().equals("API contract")) {
          var m = FENCE_OPEN.matcher(line);
          if (m.matches()) {
            fence = m.group(1);
            fenceLine = i;
          }
        }
      }
      if (fence != null) {
        problem(fenceLine, "code fence opened here is never closed");
        return false;
      }
      return true;
    }

    private void checkStructure() {
      int firstHeading = headings.isEmpty() ? lines.size() : headings.getFirst().line();
      for (int i = 0; i < firstHeading; i++) {
        if (!lines.get(i).isBlank()) {
          problem(i, "unexpected text before ## Intent");
          break;
        }
      }
      var seen = new ArrayList<String>();
      int maxIdx = -1;
      Heading maxHeading = null;
      for (var h : headings) {
        int idx = SECTIONS.indexOf(h.name());
        if (idx < 0) {
          problem(h.line(), "unknown section ## " + h.name());
        } else if (seen.contains(h.name())) {
          problem(h.line(), "duplicate section ## " + h.name());
        } else {
          seen.add(h.name());
          if (idx < maxIdx) {
            problem(
                maxHeading.line(), "## " + maxHeading.name() + " must come after ## " + h.name());
          } else {
            maxIdx = idx;
            maxHeading = h;
          }
        }
      }
      for (int idx = 0; idx < SECTIONS.size(); idx++) {
        var name = SECTIONS.get(idx);
        if (!seen.contains(name) && !name.equals(OPTIONAL_SECTION)) {
          int at = lines.size();
          for (var h : headings) {
            if (SECTIONS.indexOf(h.name()) > idx) {
              at = h.line();
              break;
            }
          }
          problem(at, "missing section ## " + name);
        }
      }
    }

    private List<StubSource> parseStubs(int from, int to) {
      var stubs = new ArrayList<StubSource>();
      String path = null;
      int pathLine = -1;
      for (int i = from; i < to; i++) {
        var line = lines.get(i);
        if (line.isBlank()) {
          continue;
        }
        if (line.startsWith("### ")) {
          if (path != null) {
            problem(pathLine, "expected a ```java code fence after ### " + path);
          }
          path = line.substring(4).strip();
          if (path.endsWith(MODIFY_SUFFIX)) {
            path = path.substring(0, path.length() - MODIFY_SUFFIX.length()).strip();
            modifies.add(path);
          }
          pathLine = i;
          continue;
        }
        var m = FENCE_OPEN.matcher(line);
        if (path != null && m.matches()) {
          int close = i + 1;
          while (close < to && !lines.get(close).equals(m.group(1))) {
            close++;
          }
          var content = String.join("\n", lines.subList(i + 1, close));
          stubs.add(new StubSource(path, content.endsWith("\n") ? content : content + "\n"));
          path = null;
          i = close;
        } else {
          problem(i, "unexpected text in ## API contract");
        }
      }
      if (path != null) {
        problem(pathLine, "expected a ```java code fence after ### " + path);
      }
      return stubs;
    }

    private List<Requirement> parseRequirements(int from, int to) {
      var reqs = new ArrayList<Requirement>();
      int i = from;
      while (i < to && !REQ_HEADING.matcher(lines.get(i)).matches()) {
        if (!lines.get(i).isBlank()) {
          problem(i, "expected ### R<n> heading");
        }
        i++;
      }
      while (i < to) {
        var id = REQ_HEADING.matcher(lines.get(i));
        id.matches();
        int next = i + 1;
        while (next < to && !lines.get(next).startsWith("### ")) {
          next++;
        }
        reqs.add(parseRequirement(id.group(1), i + 1, next));
        i = next;
        if (i < to && !REQ_HEADING.matcher(lines.get(i)).matches()) {
          problem(i, "expected ### R<n> heading");
          i++;
          while (i < to && !lines.get(i).startsWith("### ")) {
            i++;
          }
        }
      }
      return reqs;
    }

    private Requirement parseRequirement(String id, int from, int to) {
      int i = from;
      var statement = new ArrayList<String>();
      while (i < to && !lines.get(i).startsWith(EXAMPLE_PREFIX)) {
        statement.add(lines.get(i));
        i++;
      }
      var examples = new ArrayList<Example>();
      for (; i < to; i++) {
        var line = lines.get(i);
        if (line.isBlank()) {
          continue;
        }
        var m = EXAMPLE.matcher(line);
        if (m.matches()
            && !m.group(1).isBlank()
            && !m.group(2).isBlank()
            && !m.group(3).isBlank()) {
          examples.add(new Example(m.group(1).strip(), m.group(2).strip(), m.group(3).strip()));
        } else {
          problem(i, EXAMPLE_MSG);
        }
      }
      return new Requirement(id, String.join("\n", statement).strip(), examples);
    }

    private List<String> parseTests(int from, int to) {
      var out = new ArrayList<String>();
      for (int i = from; i < to; i++) {
        var line = lines.get(i);
        if (line.isBlank()) {
          continue;
        }
        var m = TEST_ENTRY.matcher(line);
        if (m.matches()) {
          out.add(m.group(1));
        } else {
          problem(
              i,
              "expected - `<path>` (the path in backticks), for example - `src/test/java/a/BTest.java`");
        }
      }
      return out;
    }

    private List<String> parseConstraints(int headingLine, int from, int to) {
      var out = new ArrayList<String>();
      boolean none = false;
      for (int i = from; i < to; i++) {
        var line = lines.get(i);
        if (line.isBlank()) {
          continue;
        }
        if (line.equals("_None._")) {
          none = true;
        } else if (line.startsWith("- ") && !line.substring(2).isBlank()) {
          out.add(line.substring(2).strip());
        } else {
          problem(i, "expected - <constraint> or _None._");
        }
      }
      if (none && !out.isEmpty()) {
        problem(headingLine, "_None._ cannot be combined with constraint bullets");
      } else if (!none
          && out.isEmpty()
          && problems.stream().noneMatch(p -> p.startsWith("line " + (headingLine + 1) + ":"))) {
        boolean anyBad = false;
        for (int i = from; i < to; i++) {
          anyBad |= !lines.get(i).isBlank();
        }
        if (!anyBad) {
          problem(headingLine, "## Constraints must list bullets or _None._");
        }
      }
      return out;
    }

    private QualityBar parseBar(int headingLine, int from, int to) {
      var content = new ArrayList<Integer>();
      for (int i = from; i < to; i++) {
        if (!lines.get(i).isBlank()) {
          content.add(i);
        }
      }
      var values = new int[3];
      boolean ok = true;
      for (int k = 0; k < 3; k++) {
        if (k >= content.size()) {
          problem(headingLine, BAR_MSGS.get(k));
          ok = false;
          continue;
        }
        var m = BAR_PATTERNS.get(k).matcher(lines.get(content.get(k)));
        if (m.matches()) {
          values[k] = Integer.parseInt(m.group(1));
        } else {
          problem(content.get(k), BAR_MSGS.get(k));
          ok = false;
        }
      }
      for (int k = 3; k < content.size(); k++) {
        problem(content.get(k), "unexpected line in ## Quality bar");
        ok = false;
      }
      return ok ? new QualityBar(values[0] / 100.0, values[1] / 100.0, values[2]) : null;
    }
  }
}
