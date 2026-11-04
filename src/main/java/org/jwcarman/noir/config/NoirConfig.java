package org.jwcarman.noir.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import org.jwcarman.noir.spec.QualityBar;

/** noir configuration, loaded from {@code ~/.noir/config.yaml} (spec §9.2). */
public record NoirConfig(
    List<String> repos,
    QualityBar qualityBar,
    double sealDiscrimination,
    Budgets budgets,
    int reworkRetries,
    Duration pollInterval,
    int infraRetries,
    Limits limits,
    Images images,
    Timeouts timeouts,
    Examiner examiner) {

  public NoirConfig {
    repos = repos == null ? List.of() : List.copyOf(repos);
  }

  /** Per-attempt agent budgets. */
  public record Budgets(int turns, Duration wallClock, BigDecimal costUsd) {
    public Budgets {
      costUsd = costUsd == null ? null : costUsd.setScale(2, RoundingMode.HALF_UP);
    }
  }

  /** Container resource limits. */
  public record Limits(double cpus, String memory, int pids) {}

  /** Pinned container image references (digests). */
  public record Images(String builder, String grader) {}

  /** Holdout test timeouts. */
  public record Timeouts(Duration perTest, Duration suite) {}

  /** Examiner agent settings. {@code model == null} means the Claude CLI default. */
  public record Examiner(String model, int maxTurns, Duration wallClock, int attempts) {}

  public static NoirConfig defaults() {
    return new NoirConfig(
        List.of(),
        new QualityBar(0.80, 0.70, 30),
        0.70,
        new Budgets(200, Duration.ofMinutes(60), new BigDecimal("10.00")),
        3,
        Duration.ofSeconds(60),
        5,
        new Limits(2.0, "4g", 512),
        new Images("noir-builder:0.1.0", null),
        new Timeouts(Duration.ofSeconds(10), Duration.ofMinutes(10)),
        new Examiner(null, 100, Duration.ofMinutes(30), 3));
  }
}
