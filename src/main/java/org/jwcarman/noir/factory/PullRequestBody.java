package org.jwcarman.noir.factory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jwcarman.noir.deliver.FileChange;
import org.jwcarman.noir.feedback.RequirementResult;

/**
 * Pull request bodies and failure comments (spec §8.3). Holdout results appear only as
 * per-requirement counts: never test names, sources, messages or traces.
 */
public final class PullRequestBody {
  /** Aggregate measurement across the changed classes. */
  /** {@code mutationScore} is {@code null} when there was nothing to mutate. */
  public record Metrics(double lineCoverage, Double mutationScore, double maxCrap) {}

  /**
   * Where the delivered work came from. Null values render as {@code unknown}. The costs are the
   * item's totals across every builder and Examiner attempt, as Claude Code reported them.
   */
  public record Provenance(
      String baseSha,
      int attempts,
      String commitmentSha256,
      String builderModel,
      String examinerModel,
      String builderImageId,
      String graderImageId,
      BigDecimal builderCostUsd,
      BigDecimal examinerCostUsd) {}

  private PullRequestBody() {}

  public static String render(
      int issue,
      Optional<Metrics> metrics,
      List<RequirementResult> results,
      Provenance p,
      List<FileChange> files,
      List<String> testsToUpdate,
      boolean draft) {
    var sb = new StringBuilder();
    if (draft) {
      sb.append(shortfall(p.attempts())).append("\n\n");
    }
    sb.append("Closes #").append(issue).append(".\n\n");
    sb.append("| Metric | Value |\n|---|---|\n");
    sb.append("| Line coverage | ")
        .append(metrics.map(m -> pct(m.lineCoverage())).orElse("n/a"))
        .append(" |\n");
    sb.append("| Mutation score | ")
        .append(
            metrics
                .map(m ->
                    m.mutationScore() == null ? "n/a (nothing to mutate)" : pct(m.mutationScore()))
                .orElse("n/a"))
        .append(" |\n");
    sb.append("| Max CRAP (changed classes) | ")
        .append(metrics.map(m -> String.format(Locale.ROOT, "%.1f", m.maxCrap())).orElse("n/a"))
        .append(" |\n\n");
    results(sb, results);
    files(sb, files, testsToUpdate);
    sb.append("\n### Provenance\n\n");
    sb.append("- Base SHA: `").append(line(p.baseSha())).append("`\n");
    sb.append("- Attempts: ").append(p.attempts()).append('\n');
    sb.append("- Commitment: `")
        .append(line(p.commitmentSha256()))
        .append("` (posted on #")
        .append(issue)
        .append(")\n");
    sb.append("- Builder model: ").append(line(p.builderModel())).append('\n');
    sb.append("- Examiner model: ").append(line(p.examinerModel())).append('\n');
    sb.append("- Builder image: `").append(line(p.builderImageId())).append("`\n");
    sb.append("- Grader image: `").append(line(p.graderImageId())).append("`\n");
    sb.append("- Cost: ").append(cost(p.builderCostUsd(), p.examinerCostUsd())).append('\n');
    return sb.toString();
  }

  /**
   * Grouped Added, Modified, Deleted, Renamed, Tests updated; path order within each group. A draft
   * pull request after a lock-check failure can delete or rename files, so those are listed too. A
   * listed test that was modified goes under Tests updated. Omitted when there are no files.
   */
  private static void files(StringBuilder sb, List<FileChange> files, List<String> testsToUpdate) {
    var added = new ArrayList<String>();
    var modified = new ArrayList<String>();
    var deleted = new ArrayList<String>();
    var renamed = new ArrayList<String>();
    var tests = new ArrayList<String>();
    for (var f : files) {
      var entry = "`" + line(f.path()) + "` (" + counts(f) + ")";
      switch (f.status()) {
        case DELETED -> deleted.add("`" + line(f.path()) + "`");
        case RENAMED ->
            renamed.add(
                "`" + line(f.oldPath()) + "` \u2192 `" + line(f.path()) + "` (" + counts(f) + ")");
        case ADDED -> (testsToUpdate.contains(f.path()) ? tests : added).add(entry);
        case MODIFIED -> (testsToUpdate.contains(f.path()) ? tests : modified).add(entry);
      }
    }
    if (files.isEmpty()) {
      return;
    }
    sb.append("\n### Files\n\n");
    added.forEach(e -> sb.append("- Added: ").append(e).append('\n'));
    modified.forEach(e -> sb.append("- Modified: ").append(e).append('\n'));
    deleted.forEach(e -> sb.append("- Deleted: ").append(e).append('\n'));
    renamed.forEach(e -> sb.append("- Renamed: ").append(e).append('\n'));
    tests.forEach(e -> sb.append("- Tests updated: ").append(e).append('\n'));
  }

  private static String counts(FileChange f) {
    return f.status() == FileChange.Status.ADDED
        ? "+" + f.added()
        : "+" + f.added() + " \u2212" + f.deleted();
  }

  private static String cost(BigDecimal builder, BigDecimal examiner) {
    if (builder == null && examiner == null) {
      return "unknown";
    }
    var total =
        (builder == null ? BigDecimal.ZERO : builder)
            .add(examiner == null ? BigDecimal.ZERO : examiner);
    return money(total)
        + " (builder "
        + money(builder)
        + ", Examiner "
        + money(examiner)
        + "; as reported by Claude Code)";
  }

  private static String money(BigDecimal v) {
    return v == null ? "unknown" : "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** The issue comment when the item failed and no attempt has commits worth a draft PR. */
  public static String failureComment(int attempts, List<RequirementResult> results) {
    var sb = new StringBuilder(shortfall(attempts));
    sb.append(" No attempt produced commits that could be delivered, so no pull request was")
        .append(" opened.\n\n");
    results(sb, results);
    return sb.toString();
  }

  private static String shortfall(int attempts) {
    return "**noir could not satisfy every requirement within " + attempts + " attempts.**";
  }

  private static void results(StringBuilder sb, List<RequirementResult> results) {
    sb.append("### Holdout results\n\n");
    if (results.isEmpty()) {
      sb.append("Not graded.\n");
    }
    for (var r : results) {
      sb.append(line(r.requirementId()))
          .append(": ")
          .append(r.passed())
          .append('/')
          .append(r.total())
          .append(" passed\n");
    }
  }

  private static String pct(double v) {
    return String.format(Locale.ROOT, "%.1f%%", v * 100);
  }

  /**
   * One line of text, also safe inside a single-backtick code span: control characters become
   * spaces and backticks are dropped (some values are reported from inside the builder container).
   */
  private static String line(String s) {
    var text = s == null ? "" : s.replaceAll("\\p{Cntrl}+", " ").replace("`", "").strip();
    return text.isEmpty() ? "unknown" : text;
  }
}
