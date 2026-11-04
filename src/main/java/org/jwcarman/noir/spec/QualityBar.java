package org.jwcarman.noir.spec;

/** Minimum line coverage and mutation score, each in (0, 1]. */
public record QualityBar(double lineCoverage, double mutationScore) {}
