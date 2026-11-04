package org.jwcarman.noir.measure;

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
    var none = MeasureOutcome.Metrics.empty();
    var exit = a.exit("build");
    if (exit.isEmpty() || exit.get() != 0) {
      return new MeasureOutcome.Shortfall(
          new FeedbackInput.BuildFailed(a.logTail("build", BUILD_LOG_LINES)), none);
    }
    var holdout = lines(a.read("holdout-sources.txt"));
    if (!holdout.isEmpty()) {
      return violation(
          holdout.stream().map(p -> "source in reserved package noir.holdout: " + p).toList());
    }
    var forbidden = lines(a.read("forbidden.txt"));
    if (!forbidden.isEmpty()) {
      return violation(forbidden.stream().map(l -> "forbidden in build output: " + l).toList());
    }
    var mismatches = ApiConformance.mismatches(a.read("javap-stub.txt"), a.read("javap-built.txt"));
    if (!mismatches.isEmpty()) {
      return violation(mismatches);
    }
    return quality(a, bar, changedClasses);
  }

  private static MeasureOutcome quality(
      MeasureArtifacts a, QualityBar bar, List<String> changedClasses) {
    Map<String, ClassCoverage> coverage = Map.of();
    Map<String, PitSummary> pit = Map.of();
    try {
      coverage = a.report("jacoco.xml").map(JacocoReport::parse).orElse(Map.of());
    } catch (RuntimeException e) {
      // unparseable report counts as no coverage
    }
    try {
      pit = a.report("mutations.xml").map(PitByClass::parse).orElse(Map.of());
    } catch (RuntimeException e) {
      // unparseable report counts as no mutants
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
      double lineCov = cov == null ? 0.0 : cov.lineCoverage();
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
            lines == 0 ? (changedClasses.isEmpty() ? 1.0 : 0.0) : covered / (double) lines,
            mutants == 0 ? 1.0 : detected / (double) mutants,
            maxCrap,
            changedClasses);
    if (failing.isEmpty()) {
      return new MeasureOutcome.Passed(metrics);
    }
    return new MeasureOutcome.Shortfall(
        new FeedbackInput.QualityShortfall(bar, failing, violations), metrics);
  }

  private static MeasureOutcome violation(List<String> mismatches) {
    return new MeasureOutcome.Shortfall(
        new FeedbackInput.ContractViolation(mismatches), MeasureOutcome.Metrics.empty());
  }

  private static List<String> lines(String text) {
    return text.isEmpty() ? List.of() : List.of(text.split("\n"));
  }
}
