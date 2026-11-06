package org.jwcarman.noir.spec;

/**
 * Minimum line coverage and mutation score, each in (0, 1], and the maximum CRAP score allowed for
 * any changed method (spec §7.1).
 */
public record QualityBar(double lineCoverage, double mutationScore, int maxCrap) {

  /**
   * The default bar for new specs and the server: line coverage 90%, mutation score 80%, max CRAP
   * 15. Real builds score far above it (100% coverage, median 100% mutation, max CRAP 8 across the
   * first 33 builds), so it leaves headroom for small classes and equivalent mutants.
   */
  public static final QualityBar DEFAULT = new QualityBar(0.90, 0.80, 15);
}
