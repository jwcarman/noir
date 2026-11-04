package org.jwcarman.noir.measure;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
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
    a.require("changed.txt");
    var holdout = lines(a.require("holdout-sources.txt"));
    if (!holdout.isEmpty()) {
      return violation(
          holdout.stream().map(p -> "source in reserved package noir.holdout: " + p).toList());
    }
    var forbidden = lines(a.require("forbidden.txt"));
    if (!forbidden.isEmpty()) {
      return violation(forbidden.stream().map(l -> "forbidden in build output: " + l).toList());
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
    if (!stubs.isEmpty() && stubJavap.isEmpty()) {
      return violation(List.of("no public API found for the stub classes"));
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
    var failing = new ArrayList<ClassQuality>();
    var violations = new ArrayList<MethodCrap>();
    int covered = 0;
    int lines = 0;
    int detected = 0;
    int mutants = 0;
    double maxCrap = 0;
    for (var name : changedClasses) {
      var cov = coverage.get(name);
      if (cov == null && !Files.isRegularFile(classFile(a, name), LinkOption.NOFOLLOW_LINKS)) {
        continue; // deleted class: nothing to measure
      }
      double lineCov = cov == null ? 1.0 : cov.lineCoverage(); // no executable code
      if (cov != null) {
        covered += cov.linesCovered();
        lines += cov.linesCovered() + cov.linesMissed();
      }
      var summary = pit.get(name);
      double score = summary == null || summary.total() == 0 ? 1.0 : summary.score();
      if (summary != null) {
        detected += summary.detected();
        mutants += summary.total();
      }
      boolean bad = lineCov < bar.lineCoverage() || score < bar.mutationScore();
      if (cov != null) {
        for (var m : cov.methods()) {
          maxCrap = Math.max(maxCrap, m.crap());
          if (m.crap() > bar.maxCrap()) {
            violations.add(m);
            bad = true;
          }
        }
      }
      if (bad) {
        failing.add(new ClassQuality(name, lineCov, score));
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

  private static Path classFile(MeasureArtifacts a, String fqn) {
    return a.exportClasses().resolve(fqn.replace('.', '/') + ".class");
  }

  private static MeasureOutcome violation(List<String> mismatches) {
    return new MeasureOutcome.Shortfall(
        new FeedbackInput.ContractViolation(mismatches), MeasureOutcome.Metrics.empty());
  }

  private static List<String> lines(String text) {
    return text.isEmpty() ? List.of() : List.of(text.split("\n"));
  }
}
