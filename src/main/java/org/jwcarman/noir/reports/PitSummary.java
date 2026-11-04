package org.jwcarman.noir.reports;

/** Totals from a PIT mutations report. */
public record PitSummary(int total, int detected) {
  public double score() {
    return total == 0 ? 0 : detected / (double) total;
  }
}
