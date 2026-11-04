package org.jwcarman.noir.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.noir.lifecycle.Category;
import org.jwcarman.noir.lifecycle.State;

/** Experiment metrics (spec §10). A null value means there was no data. */
public record ExperimentReport(
    int items,
    Map<State, Integer> byState,
    Double firstPassRate,
    int firstPassPassed,
    int firstPassTotal,
    Double meanReworkCycles,
    Map<Category, Integer> failureCategories,
    BigDecimal totalCostUsd,
    BigDecimal meanCostUsdPerItem,
    Duration medianWallTime,
    Duration medianHumanTime,
    int accepted,
    int modified,
    int rejected,
    int completed,
    int holdoutDefects,
    AutoMerge autoMerge) {

  /** Progress toward the auto-merge rule. */
  public record AutoMerge(
      int completed,
      int required,
      Double acceptedRate,
      double requiredRate,
      int holdoutDefects,
      boolean eligible) {}

  public String render() {
    var sb = new StringBuilder();
    sb.append("Experiment (").append(items).append(items == 1 ? " item)" : " items)").append('\n');
    line(
        sb,
        "First-pass rate",
        firstPassRate == null
            ? "n/a"
            : percent(firstPassRate) + " (" + firstPassPassed + "/" + firstPassTotal + ")");
    line(
        sb,
        "Rework cycles",
        meanReworkCycles == null ? "n/a" : String.format("%.1f per item", meanReworkCycles));
    line(sb, "Failure categories", failureCategories.isEmpty() ? "none" : categoriesText());
    line(
        sb,
        "Cost",
        totalCostUsd == null
            ? "n/a"
            : money(totalCostUsd) + " total, " + money(meanCostUsdPerItem) + " per item");
    line(sb, "Wall time (median)", duration(medianWallTime));
    line(sb, "Human time (median)", duration(medianHumanTime));
    line(
        sb,
        "PR disposition",
        accepted + " accepted, " + modified + " modified, " + rejected + " rejected");
    line(sb, "Holdout defects", String.valueOf(holdoutDefects));
    line(sb, "Auto-merge", autoMergeText());
    return sb.toString();
  }

  private String categoriesText() {
    var parts = new ArrayList<String>();
    failureCategories.forEach((c, n) -> parts.add(c + " " + n));
    return String.join(", ", parts);
  }

  private String autoMergeText() {
    if (autoMerge.eligible()) {
      return "eligible";
    }
    if (autoMerge.completed() < autoMerge.required()) {
      return "not eligible: "
          + autoMerge.completed()
          + "/"
          + autoMerge.required()
          + " completed items";
    }
    if (autoMerge.acceptedRate() < autoMerge.requiredRate()) {
      return "not eligible: "
          + percent(autoMerge.acceptedRate())
          + " accepted (need "
          + percent(autoMerge.requiredRate())
          + ")";
    }
    return "not eligible: "
        + autoMerge.holdoutDefects()
        + " holdout defect"
        + (autoMerge.holdoutDefects() == 1 ? "" : "s");
  }

  private static void line(StringBuilder sb, String label, String value) {
    sb.append(String.format("  %-20s %s%n", label, value));
  }

  private static String percent(double rate) {
    return new BigDecimal(rate * 100).setScale(0, RoundingMode.HALF_UP) + "%";
  }

  private static String money(BigDecimal v) {
    return "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String duration(Duration d) {
    if (d == null) {
      return "n/a";
    }
    long total = d.getSeconds();
    long h = total / 3600;
    long m = (total % 3600) / 60;
    long s = total % 60;
    if (h > 0) {
      return h + "h " + m + "m";
    }
    return m > 0 ? m + "m " + s + "s" : s + "s";
  }

  /** JSON-ready view: durations ISO-8601, rates and money as numbers, null for no data. */
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("items", items);
    m.put("byState", byState);
    m.put("firstPassRate", firstPassRate);
    m.put("meanReworkCycles", meanReworkCycles);
    m.put("failureCategories", failureCategories);
    m.put("totalCostUsd", totalCostUsd);
    m.put("meanCostUsdPerItem", meanCostUsdPerItem);
    m.put("medianWallTime", medianWallTime == null ? null : medianWallTime.toString());
    m.put("medianHumanTime", medianHumanTime == null ? null : medianHumanTime.toString());
    m.put("accepted", accepted);
    m.put("modified", modified);
    m.put("rejected", rejected);
    m.put("completed", completed);
    m.put("holdoutDefects", holdoutDefects);
    var a = new LinkedHashMap<String, Object>();
    a.put("completed", autoMerge.completed());
    a.put("required", autoMerge.required());
    a.put("acceptedRate", autoMerge.acceptedRate());
    a.put("requiredRate", autoMerge.requiredRate());
    a.put("holdoutDefects", autoMerge.holdoutDefects());
    a.put("eligible", autoMerge.eligible());
    m.put("autoMerge", a);
    return m;
  }
}
