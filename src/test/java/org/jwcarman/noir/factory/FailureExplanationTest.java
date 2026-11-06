package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.spec.QualityBar;
import org.jwcarman.noir.store.Event;

class FailureExplanationTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
  static final QualityBar BAR = new QualityBar(0.90, 0.80, 15);

  final List<Event> events = new ArrayList<>();

  void ev(String type, int attempt, Map<String, Object> data) {
    events.add(Event.of(CLOCK, "id", type, attempt, data));
  }

  void failed(int attempt, String category) {
    ev("transition", attempt, Map.of("to", "REWORK", "category", category));
  }

  String render() {
    return FailureExplanation.render(events, BAR);
  }

  @Test
  void romanShape() {
    ev(
        "measurement",
        1,
        Map.of(
            "category",
            "BUILD_FAILED",
            "lockedTestsFailed",
            List.of("a.RomanTest#rejects(int)[1]", "a.RomanTest#rejects(int)[2]")));
    failed(1, "BUILD_FAILED");
    for (int n = 2; n <= 4; n++) {
      ev("build_attempt", n, Map.of("producedCommits", false, "lastMessage", ""));
      failed(n, "NO_OUTPUT");
    }
    assertThat(render())
        .isEqualTo(
            "### Why it failed\n\n"
                + "- Attempt 1: the build failed.\n"
                + "- Attempts 2–4: no commits; the builder stopped without changing anything.\n"
                + "\nExisting tests that failed: `a.RomanTest#rejects(int)[1]`,"
                + " `a.RomanTest#rejects(int)[2]`. This spec doesn't allow them to change. If the"
                + " change is meant to alter what they check, list `src/test/java/a/RomanTest.java`"
                + " under `## Tests to update`, then add `noir:ready` again.\n");
  }

  @Test
  void eachCategoryHasItsLine() {
    ev("measurement", 1, Map.of("violation", "You modified `pom.xml`."));
    failed(1, "CONTRACT_VIOLATION");
    ev("measurement", 2, Map.of("lineCoverage", 0.72, "mutationScore", 0.85, "maxCrap", 31.0));
    failed(2, "QUALITY_SHORTFALL");
    ev(
        "grade",
        3,
        Map.of(
            "result",
            "failed",
            "results",
            List.of(
                Map.of("requirement", "R1", "passed", 2, "total", 3),
                Map.of("requirement", "R2", "passed", 1, "total", 1))));
    failed(3, "BEHAVIOR_FAILURE");
    ev("build_attempt", 4, Map.of("lastMessage", "I cannot change\nRomanTest."));
    failed(4, "NO_OUTPUT");

    assertThat(render())
        .isEqualTo(
            "### Why it failed\n\n"
                + "- Attempt 1: contract violation: You modified `pom.xml`.\n"
                + "- Attempt 2: quality shortfall: line coverage 72.0% (bar 90.0%), max CRAP 31.0"
                + " (bar 15).\n"
                + "- Attempt 3: hidden tests failed: R1 2/3 passed, R2 1/1 passed.\n"
                + "- Attempt 4: no commits; the builder stopped without changing anything.\n"
                + "  The builder's last message:\n"
                + "  > I cannot change\n"
                + "  > RomanTest.\n");
  }

  @Test
  void onlyIdenticalConsecutiveLinesCollapse() {
    failed(1, "BUILD_FAILED");
    ev("build_attempt", 2, Map.of());
    failed(2, "NO_OUTPUT");
    failed(3, "BUILD_FAILED");
    failed(4, "BUILD_FAILED");
    assertThat(render())
        .contains(
            "- Attempt 1: the build failed.\n"
                + "- Attempt 2: no commits; the builder stopped without changing anything.\n"
                + "- Attempts 3–4: the build failed.\n");
  }

  @Test
  void onlyTheLatestRunCounts() {
    failed(1, "CONTRACT_VIOLATION");
    ev("restarted", 1, Map.of("run", 1));
    failed(1, "BUILD_FAILED");
    assertThat(render()).contains("- Attempt 1: the build failed.").doesNotContain("contract");
  }

  @Test
  void lockedTestsAreCappedAtTwenty() {
    var many = IntStream.range(0, 25).mapToObj(i -> "a.T#t" + (100 + i)).toList();
    ev("measurement", 1, Map.of("lockedTestsFailed", many));
    failed(1, "BUILD_FAILED");
    assertThat(render()).contains("`a.T#t119`, and 5 more. This spec").doesNotContain("a.T#t120");
  }

  @Test
  void textIsSanitized() {
    ev("measurement", 1, Map.of("violation", "bad\u0007text with `ticks`"));
    failed(1, "CONTRACT_VIOLATION");
    // Free text keeps backticks (it is not inside a code span); control characters go.
    assertThat(render()).contains("contract violation: bad text with `ticks`");
  }

  @Test
  void theFinalAttemptsCategoryIsPassedIn() {
    // The failure comment is posted before the final transition's event is written.
    failed(1, "BUILD_FAILED");
    assertThat(FailureExplanation.render(events, BAR, 2, "BUILD_FAILED"))
        .contains("- Attempts 1\u20132: the build failed.\n");
  }
}
