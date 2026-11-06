package org.jwcarman.noir.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mechanical checks on a spec (spec §5.1). Subjective-language checks are left to the Specifier
 * prompt.
 */
public final class SpecLint {
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

  private SpecLint() {}

  public static List<String> lint(Spec spec) {
    var problems = new ArrayList<String>();
    if (spec.schemaVersion() != 1 && spec.schemaVersion() != 2) {
      problems.add("schemaVersion must be 1 or 2");
    }
    if (!ItemId.isValid(spec.id())) {
      problems.add("id must be a valid noir item id");
    }
    if (spec.repo() == null || !REPO.matcher(spec.repo()).matches()) {
      problems.add("repo must be owner/name");
    }
    if (spec.baseSha() == null || !SHA.matcher(spec.baseSha()).matches()) {
      problems.add("baseSha must be a 40-character hex commit SHA");
    }
    problems.addAll(lintContent(spec));
    return problems;
  }

  /** Every rule except the identity fields (schemaVersion, id, repo, baseSha). */
  public static List<String> lintContent(Spec spec) {
    var problems = new ArrayList<String>();
    notBlank(spec.title(), "title", problems);
    notBlank(spec.intent(), "intent", problems);
    lintRequirements(spec.requirements(), problems);
    lintQualityBar(spec.qualityBar(), problems);
    lintStubs(spec.apiStubs(), problems);
    lintModifies(spec, problems);
    return problems;
  }

  private static void lintRequirements(List<Requirement> reqs, List<String> problems) {
    if (reqs.isEmpty()) {
      problems.add("at least one requirement is required");
      return;
    }
    for (int i = 0; i < reqs.size(); i++) {
      var r = reqs.get(i);
      var expected = "R" + (i + 1);
      if (!expected.equals(r.id())) {
        problems.add(
            "requirement ids must be R1..Rn in order; found " + r.id() + " at position " + (i + 1));
      }
      notBlank(r.statement(), r.id() + " statement", problems);
      if (r.examples().isEmpty()) {
        problems.add(r.id() + " has no examples");
      }
      for (int j = 0; j < r.examples().size(); j++) {
        var e = r.examples().get(j);
        var prefix = r.id() + " example " + (j + 1) + " ";
        notBlank(e.given(), prefix + "'given'", problems);
        notBlank(e.when(), prefix + "'when'", problems);
        notBlank(e.then(), prefix + "'then'", problems);
      }
    }
  }

  private static void lintQualityBar(QualityBar bar, List<String> problems) {
    if (bar == null) {
      problems.add("qualityBar is required");
      return;
    }
    if (!(bar.lineCoverage() > 0 && bar.lineCoverage() <= 1)) {
      problems.add("qualityBar.lineCoverage must be in (0, 1]");
    }
    if (!(bar.mutationScore() > 0 && bar.mutationScore() <= 1)) {
      problems.add("qualityBar.mutationScore must be in (0, 1]");
    }
    if (bar.maxCrap() < 1) {
      problems.add("qualityBar.maxCrap must be at least 1");
    }
  }

  private static void lintStubs(List<String> stubs, List<String> problems) {
    if (stubs.isEmpty()) {
      problems.add("at least one API stub is required");
    }
    for (var s : stubs) {
      if (!StubLint.isWellFormed(s)) {
        problems.add(
            "apiStubs entry "
                + s
                + " must be a relative path under src/main/java/ ending in .java");
      }
    }
  }

  private static void lintModifies(Spec spec, List<String> problems) {
    var seen = new java.util.HashSet<String>();
    for (var m : spec.modifies()) {
      if (!StubLint.isWellFormed(m)) {
        problems.add(
            "API contract (modify) entry "
                + m
                + " must be a relative path under src/main/java/ ending in .java");
      }
      if (!seen.add(m)) {
        problems.add("API contract lists " + m + " as (modify) more than once");
      }
    }
    var tests = new java.util.HashSet<String>();
    for (var t : spec.testsToUpdate()) {
      if (!isTestPath(t)) {
        problems.add(
            "Tests to update entry "
                + t
                + " must be a relative path under src/test/java/ ending in .java");
      }
      if (!tests.add(t)) {
        problems.add("Tests to update lists " + t + " more than once");
      }
    }
    for (var m : seen) {
      if (tests.contains(m)) {
        problems.add(
            m + " cannot be both an API contract (modify) entry and a Tests to update entry");
      }
    }
  }

  private static boolean isTestPath(String p) {
    return p != null
        && p.startsWith("src/test/java/")
        && p.endsWith(".java")
        && p.length() > "src/test/java/.java".length()
        && !p.contains("\\")
        && java.util.Arrays.stream(p.split("/", -1))
            .noneMatch(seg -> seg.isEmpty() || seg.equals(".") || seg.equals(".."));
  }

  private static void notBlank(String value, String field, List<String> problems) {
    if (value == null || value.isBlank()) {
      problems.add(field + " must not be blank");
    }
  }
}
