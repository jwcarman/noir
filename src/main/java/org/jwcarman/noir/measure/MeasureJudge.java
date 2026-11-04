package org.jwcarman.noir.measure;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.feedback.ClassQuality;
import org.jwcarman.noir.feedback.FeedbackInput;
import org.jwcarman.noir.reports.PitSummary;
import org.jwcarman.noir.spec.QualityBar;

/** Decides whether a measured attempt meets the quality bar. */
public final class MeasureJudge {
  private static final int BUILD_LOG_LINES = 200;

  private MeasureJudge() {}

  public static MeasureOutcome judge(
      MeasureArtifacts a, QualityBar bar, List<String> changedClasses) {
    int build = a.requireExit("build");
    int holdoutStep = a.requireExit("holdout-sources");
    int forbiddenStep = a.requireExit("forbidden");
    int api = a.requireExit("api");
    int changedStep = a.requireExit("changed");
    int deps = a.requireExit("deps");
    int pit = a.requireExit("pit");
    if (build != 0) {
      return new MeasureOutcome.Shortfall(
          new FeedbackInput.BuildFailed(a.logTail("build", BUILD_LOG_LINES)),
          MeasureOutcome.Metrics.empty());
    }
    requireInfra("holdout-sources", holdoutStep);
    requireInfra("forbidden", forbiddenStep);
    requireInfra("changed", changedStep);
    requireInfra("deps", deps);
    var misplaced =
        lines(a.require("changed.txt")).stream()
            .map(String::strip)
            .filter(l ->
                l.endsWith(".java")
                    && !l.startsWith("src/main/java/")
                    && !l.startsWith("src/test/java/"))
            .map(l -> "Java sources must live under src/main/java or src/test/java: " + l)
            .toList();
    if (!misplaced.isEmpty()) {
      return violation(misplaced);
    }
    var holdout = lines(a.require("holdout-sources.txt"));
    if (!holdout.isEmpty()) {
      return violation(
          holdout.stream().map(p -> "source in reserved package noir.holdout: " + p).toList());
    }
    var forbidden = lines(a.require("forbidden.txt"));
    if (!forbidden.isEmpty()) {
      return violation(forbidden.stream().map(l -> "forbidden in build output: " + l).toList());
    }
    var generated = lines(a.require("generated.txt"));
    if (!generated.isEmpty()) {
      return violation(
          generated.stream()
              .map(c ->
                  "Remove @…Generated annotations; generated code is excluded from measurement: "
                      + c)
              .toList());
    }
    if (api != 0) {
      return violation(
          List.of(
              "API stubs do not compile against the built classes:\n"
                  + a.logTail("api", BUILD_LOG_LINES)));
    }
    var stubs = a.require("stubs.txt");
    var stubJavap = a.require("javap-stub.txt");
    var builtJavap = a.require("javap-built.txt");
    var declared = ApiConformance.classNames(stubJavap);
    var uncompiled =
        lines(stubs).stream()
            .map(String::strip)
            .filter(f -> !f.isEmpty() && !declared.contains(f))
            .map(f -> "missing: " + f + " (stub did not compile)")
            .toList();
    if (!uncompiled.isEmpty()) {
      return violation(uncompiled);
    }
    var mismatches = ApiConformance.mismatches(stubJavap, builtJavap);
    if (!mismatches.isEmpty()) {
      return violation(mismatches);
    }
    if (pit != 0) {
      return new MeasureOutcome.Shortfall(
          new FeedbackInput.BuildFailed(
              "Mutation testing failed:\n" + a.logTail("pit", BUILD_LOG_LINES)),
          MeasureOutcome.Metrics.empty());
    }
    return quality(a, bar, changedClasses);
  }

  private static void requireInfra(String step, int exit) {
    if (exit != 0) {
      throw new IllegalStateException("measurement incomplete: " + step + " exited " + exit);
    }
  }

  private static MeasureOutcome quality(
      MeasureArtifacts a, QualityBar bar, List<String> changedClasses) {
    Map<String, ClassCoverage> coverage = Map.of();
    Map<String, PitSummary> pit = Map.of();
    if (!changedClasses.isEmpty()) {
      try {
        coverage = JacocoReport.parse(a.requireReport("jacoco.xml"));
        pit = PitByClass.parse(a.requireReport("mutations.xml"));
      } catch (RuntimeException e) {
        return new MeasureOutcome.Shortfall(
            new FeedbackInput.BuildFailed("Coverage or mutation report missing or unreadable"),
            MeasureOutcome.Metrics.empty());
      }
    }
    var measured = new LinkedHashMap<String, ClassCoverage>();
    boolean anyClassFile = false;
    var unmatched = new ArrayList<String>();
    var changedPackages = new java.util.HashSet<String>();
    for (var name : changedClasses) {
      int dot = name.lastIndexOf('.');
      var pkg = dot < 0 ? "" : name.substring(0, dot);
      var source = name.substring(dot + 1) + ".java";
      var found =
          coverage.values().stream()
              .filter(c -> c.packageName().equals(pkg) && c.sourceFile().equals(source))
              .toList();
      found.forEach(c -> measured.putIfAbsent(c.className(), c));
      changedPackages.add(pkg);
      if (found.isEmpty()) {
        if (hasClassFiles(a, pkg, name.substring(dot + 1))) {
          anyClassFile = true;
        } else {
          unmatched.add(
              "src/main/java/"
                  + name.replace('.', '/')
                  + ".java produced no class matching its directory; "
                  + "the package declaration must match the directory");
        }
      }
    }
    if (!unmatched.isEmpty()) {
      return violation(unmatched);
    }
    for (var cov : coverage.values()) {
      if (changedPackages.contains(cov.packageName()) && cov.lacksDebugInfo()) {
        return new MeasureOutcome.Shortfall(
            new FeedbackInput.BuildFailed(
                "Compile with debug information (javac -g); line coverage could not be measured: "
                    + cov.className()),
            MeasureOutcome.Metrics.empty());
      }
    }
    if (coverage.isEmpty() && anyClassFile) {
      return new MeasureOutcome.Shortfall(
          new FeedbackInput.BuildFailed("Coverage report contains none of the changed classes"),
          MeasureOutcome.Metrics.empty());
    }
    var failing = new ArrayList<ClassQuality>();
    var violations = new ArrayList<MethodCrap>();
    int covered = 0;
    int lines = 0;
    int detected = 0;
    int mutants = 0;
    double maxCrap = 0;
    for (var cov : measured.values()) {
      double lineCov = cov.lineCoverage();
      int classLines = cov.linesCovered() + cov.linesMissed();
      covered += cov.linesCovered();
      lines += classLines;
      var summary = pit.get(cov.className());
      double score;
      if (summary == null || summary.total() == 0) {
        score = 1.0;
      } else {
        score = summary.score();
        detected += summary.detected();
        mutants += summary.total();
      }
      boolean bad = lineCov < bar.lineCoverage() || score < bar.mutationScore();
      for (var m : cov.methods()) {
        maxCrap = Math.max(maxCrap, m.crap());
        if (m.crap() > bar.maxCrap()) {
          violations.add(m);
          bad = true;
        }
      }
      if (bad) {
        failing.add(new ClassQuality(cov.className(), lineCov, score));
      }
    }
    var metrics =
        new MeasureOutcome.Metrics(
            lines == 0 ? 1.0 : covered / (double) lines,
            mutants == 0 ? 1.0 : detected / (double) mutants,
            maxCrap,
            changedClasses);
    if (failing.isEmpty()) {
      return new MeasureOutcome.Passed(metrics);
    }
    return new MeasureOutcome.Shortfall(
        new FeedbackInput.QualityShortfall(bar, failing, violations), metrics);
  }

  /**
   * Whether {@code export/classes/<pkg>/} holds {@code Simple.class} or {@code Simple$*.class}.
   * Detection is by file name; classes in a differently named file are found through JaCoCo.
   */
  private static boolean hasClassFiles(MeasureArtifacts a, String pkg, String simple) {
    var dir = a.exportClasses().resolve(pkg.replace('.', '/'));
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try (var files = Files.list(dir)) {
      return files.anyMatch(p -> {
        var n = p.getFileName().toString();
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
            && (n.equals(simple + ".class")
                || (n.startsWith(simple + "$") && n.endsWith(".class")));
      });
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private static MeasureOutcome violation(List<String> mismatches) {
    return new MeasureOutcome.Shortfall(
        new FeedbackInput.ContractViolation(mismatches), MeasureOutcome.Metrics.empty());
  }

  private static List<String> lines(String text) {
    return text.isEmpty() ? List.of() : List.of(text.split("\n"));
  }
}
