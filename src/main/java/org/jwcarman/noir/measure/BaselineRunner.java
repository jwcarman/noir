package org.jwcarman.noir.measure;

import java.nio.file.Path;
import java.util.List;
import org.jwcarman.noir.feedback.TestOutcome;

/**
 * Runs the base commit's own tests once per item, at sealing: the tests that pass there must still
 * pass on every attempt's branch ({@link TestInventory}). Infrastructure failures throw; a base
 * build that fails is an outcome ({@code exit != 0}).
 */
@FunctionalInterface
public interface BaselineRunner {
  Baseline baseline(String itemId, Path baseBundle, String branch, Path outDir);

  /** The base build's exit, its test outcomes and the tail of its log. */
  record Baseline(int exit, List<TestOutcome> outcomes, String logTail) {
    public Baseline {
      outcomes = List.copyOf(outcomes);
    }
  }
}
