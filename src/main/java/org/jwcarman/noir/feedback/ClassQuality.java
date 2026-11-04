package org.jwcarman.noir.feedback;

/** Measured quality of one changed class. */
public record ClassQuality(String className, double lineCoverage, double mutationScore) {}
