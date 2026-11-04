package org.jwcarman.noir.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.noir.feedback.TestOutcome.Result.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.feedback.TestOutcome;

class JUnitXmlReportsTest {
  @Test
  void parsesOutcomes() throws Exception {
    var dir = Path.of(getClass().getResource("/fixtures/reports/junit").toURI());
    assertThat(JUnitXmlReports.parse(dir))
        .extracting(TestOutcome::testId, TestOutcome::result)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("noir.holdout.x.CounterTest#passes()", PASSED),
            org.assertj.core.groups.Tuple.tuple("noir.holdout.x.CounterTest#fails()", FAILED),
            org.assertj.core.groups.Tuple.tuple("noir.holdout.x.CounterTest#errors()", FAILED),
            org.assertj.core.groups.Tuple.tuple("noir.holdout.x.CounterTest#hangs()", TIMED_OUT));
    assertThat(JUnitXmlReports.parse(dir).get(1).message()).isEqualTo("expected: <1> but was: <2>");
  }

  @Test
  void missingDirIsEmpty(@TempDir Path tmp) {
    assertThat(JUnitXmlReports.parse(tmp.resolve("nope"))).isEmpty();
  }

  @Test
  void rejectsDoctype(@TempDir Path tmp) throws Exception {
    Files.writeString(
        tmp.resolve("TEST-x.xml"),
        "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testsuite>&e;</testsuite>");
    assertThatThrownBy(() -> JUnitXmlReports.parse(tmp))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
