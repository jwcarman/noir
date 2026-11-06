package org.jwcarman.noir.seal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns seal-check problems into text safe for a public issue comment. Problems name holdout tests
 * and carry build logs, so each is mapped to a fixed phrase; only repository-shape problems (which
 * describe the target repository, never the suite) are passed through, and only when they match
 * their exact known form.
 */
public final class SealProblems {
  static final String STUBS = "the API stubs do not compile";
  static final String HOLDOUT_COMPILE = "the hidden tests do not compile against the stubs";
  static final String NO_TESTS = "no hidden tests were found";
  static final String REF_COMPILE = "the reference implementation does not compile";
  static final String REF_FAILED = "the reference implementation failed the hidden tests";
  static final String MUTATION = "mutation score below the required threshold";
  static final String EXISTING_FAILED = "existing tests fail with the reference implementation: ";
  static final String EXISTING_COMPILE =
      "the existing tests do not compile with the reference implementation";
  static final String STRUCTURE = "the hidden tests did not meet noir's structural rules";
  static final String INCOMPLETE = "a seal check did not complete";
  static final String OTHER = "other check failures";

  private static final Pattern PASSTHROUGH =
      Pattern.compile(
          "pom\\.xml not found at repository root"
              + "|multi-module Maven builds are not supported"
              + "|maven\\.compiler\\.release must be 21 \\(found (\\d+|unset)\\)");

  /** {@code HoldoutAnalyzer} problems: {@code <file>.java[#method]: …} or {@code R<n> has no…}. */
  private static final Pattern ANALYZER =
      Pattern.compile("(?s)([^\\n:]+\\.java(#[^\\n:]*)?: .*|R\\d+ has no holdout tests)");

  /** Check-4 problems: the class (a Java binary name outside the holdout tree) and the method. */
  private static final Pattern EXISTING =
      Pattern.compile(
          Pattern.quote(SealJudge.EXISTING_TEST_FAILS)
              + "((?!noir\\.holdout\\.)[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)#[^\\n]*");

  private enum Kind {
    PASSTHROUGH,
    STUBS,
    HOLDOUT_COMPILE,
    NO_TESTS,
    VACUOUS,
    REF_COMPILE,
    REF_FAILED,
    MUTATION,
    EXISTING_FAILED,
    EXISTING_COMPILE,
    STRUCTURE,
    INCOMPLETE,
    OTHER
  }

  private SealProblems() {}

  /** One fixed phrase per kind of problem, in a fixed order; the vacuous kind is counted. */
  public static List<String> publicSummary(List<String> problems) {
    var passthrough = new LinkedHashSet<String>();
    var kinds = new LinkedHashSet<Kind>();
    var existing = new LinkedHashSet<String>();
    int vacuous = 0;
    for (var p : problems) {
      var kind = classify(p);
      if (kind == Kind.PASSTHROUGH) {
        passthrough.add(p);
      } else if (kind == Kind.EXISTING_FAILED) {
        existing.add(existingTestClass(p).orElseThrow());
      } else if (kind == Kind.VACUOUS) {
        vacuous++;
      }
      kinds.add(kind);
    }
    var out = new ArrayList<String>(passthrough);
    for (var kind : Kind.values()) {
      if (kind == Kind.PASSTHROUGH || !kinds.contains(kind)) {
        continue;
      }
      out.add(
          switch (kind) {
            case STUBS -> STUBS;
            case HOLDOUT_COMPILE -> HOLDOUT_COMPILE;
            case NO_TESTS -> NO_TESTS;
            case VACUOUS ->
                vacuous
                    + (vacuous == 1 ? " hidden test" : " hidden tests")
                    + " passed against"
                    + " the stubs";
            case REF_COMPILE -> REF_COMPILE;
            case REF_FAILED -> REF_FAILED;
            case MUTATION -> MUTATION;
            case EXISTING_FAILED -> EXISTING_FAILED + codeList(existing);
            case EXISTING_COMPILE -> EXISTING_COMPILE;
            case STRUCTURE -> STRUCTURE;
            case INCOMPLETE -> INCOMPLETE;
            case OTHER, PASSTHROUGH -> OTHER;
          });
    }
    return out;
  }

  /**
   * The test class named by a check-4 problem ({@code existing test fails with the reference:
   * <Class>#<method>}); empty for any other problem, or one whose class is not a plain Java name.
   */
  public static Optional<String> existingTestClass(String problem) {
    var m = EXISTING.matcher(problem);
    return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
  }

  private static String codeList(Iterable<String> items) {
    var parts = new ArrayList<String>();
    items.forEach(i -> parts.add("`" + i + "`"));
    return String.join(", ", parts);
  }

  private static Kind classify(String p) {
    if (PASSTHROUGH.matcher(p).matches()) {
      return Kind.PASSTHROUGH;
    }
    if (existingTestClass(p).isPresent()) {
      return Kind.EXISTING_FAILED;
    }
    if (p.startsWith(SealJudge.EXISTING_TESTS_DO_NOT_COMPILE + ":")) {
      return Kind.EXISTING_COMPILE;
    }
    if (p.startsWith("API stubs do not compile")) {
      return Kind.STUBS;
    }
    if (p.startsWith("holdout tests do not compile against the stubs")) {
      return Kind.HOLDOUT_COMPILE;
    }
    if (p.equals("no holdout tests were discovered")) {
      return Kind.NO_TESTS;
    }
    if (p.startsWith("holdout test passes against the stubs (vacuous)")) {
      return Kind.VACUOUS;
    }
    if (p.startsWith("reference implementation does not compile")) {
      return Kind.REF_COMPILE;
    }
    if (p.startsWith("holdout test fails against the reference implementation")
        || p.startsWith("holdout test is nondeterministic")) {
      return Kind.REF_FAILED;
    }
    if (p.startsWith("holdout tests are not discriminating enough")
        || p.startsWith("mutation testing produced no mutants")) {
      return Kind.MUTATION;
    }
    if (p.startsWith("holdout test is skipped")
        || p.startsWith("Examiner output must not contain symlinks")
        || p.startsWith("Sealed file is not valid UTF-8")
        || ANALYZER.matcher(p).matches()) {
      return Kind.STRUCTURE;
    }
    if (p.startsWith("seal check did not complete step")
        || p.startsWith("seal check failed at step")) {
      return Kind.INCOMPLETE;
    }
    return Kind.OTHER;
  }
}
