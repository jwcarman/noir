package org.jwcarman.noir.measure;

/** The CRAP (change risk anti-patterns) score of a method. */
public final class Crap {
  private Crap() {}

  public static double score(int complexity, double coverage) {
    double c = complexity;
    return c * c * Math.pow(1 - coverage, 3) + c;
  }
}
