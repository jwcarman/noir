package org.jwcarman.noir.measure;

/** CRAP score of one method. */
public record MethodCrap(
    String className, String method, int complexity, double lineCoverage, double crap) {}
