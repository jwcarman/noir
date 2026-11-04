package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JacocoReportTest {
  private static final Path XML = Path.of("src/test/resources/fixtures/measure/jacoco.xml");

  @Test
  void mergesNestedClassesAndComputesCrap() {
    var report = JacocoReport.parse(XML);
    assertThat(report).containsOnlyKeys("com.example.Calc");
    var calc = report.get("com.example.Calc");
    assertThat(calc.linesCovered()).isEqualTo(4);
    assertThat(calc.linesMissed()).isEqualTo(4);
    assertThat(calc.lineCoverage()).isEqualTo(0.5);
    assertThat(calc.methods())
        .extracting(MethodCrap::method)
        .containsExactly("add(int,int)", "pick(String,int[])", "<init>()");
    var pick = calc.methods().get(1);
    assertThat(pick.complexity()).isEqualTo(5);
    assertThat(pick.lineCoverage()).isEqualTo(0.25);
    assertThat(pick.crap()).isEqualTo(Crap.score(5, 0.25));
    assertThat(pick.className()).isEqualTo("com.example.Calc");
  }

  @Test
  void noLinesMeansFullCoverage() {
    assertThat(new ClassCoverage("X", 0, 0, java.util.List.of()).lineCoverage()).isEqualTo(1.0);
  }

  @Test
  void methodWithoutLineCounterHasZeroCoverage(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    var xml = dir.resolve("r.xml");
    java.nio.file.Files.writeString(
        xml,
        "<report><package name='a'><class name='a/B'><method name='m' desc='(JZ)V'>"
            + "<counter type='COMPLEXITY' missed='1' covered='0'/></method></class></package></report>");
    var m = JacocoReport.parse(xml).get("a.B").methods().get(0);
    assertThat(m.lineCoverage()).isEqualTo(0.0);
    assertThat(m.method()).isEqualTo("m(long,boolean)");
  }

  @Test
  void externalEntitiesAreNeverResolved(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    var secret = dir.resolve("secret.txt");
    java.nio.file.Files.writeString(secret, "TOPSECRET");
    var xml = dir.resolve("r.xml");
    java.nio.file.Files.writeString(
        xml,
        "<!DOCTYPE report [<!ENTITY x SYSTEM 'file://"
            + secret.toAbsolutePath()
            + "'>]><report><package name='a'><class name='a/B'><method name='m' desc='()V'>&x;</method></class></package></report>");
    var parsed = JacocoReport.parse(xml);
    assertThat(parsed).containsKey("a.B");
  }
}
