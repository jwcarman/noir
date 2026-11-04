package org.jwcarman.noir.spec;

/**
 * Minimum line coverage and mutation score, each in (0, 1], and the maximum CRAP score allowed for
 * any changed method (spec §7.1).
 */
public record QualityBar(double lineCoverage, double mutationScore, int maxCrap) {}
