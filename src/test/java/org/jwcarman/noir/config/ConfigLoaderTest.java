package org.jwcarman.noir.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.spec.QualityBar;

class ConfigLoaderTest {
  @TempDir Path tmp;

  @Test
  void missingFileGivesDefaults() {
    var c = ConfigLoader.load(tmp.resolve("config.yaml"));
    assertThat(c).isEqualTo(NoirConfig.defaults());
    assertThat(c.repos()).isEmpty();
    assertThat(c.qualityBar()).isEqualTo(new QualityBar(0.90, 0.80, 15));
    assertThat(c.sealDiscrimination()).isEqualTo(0.70);
    assertThat(c.hiddenTests()).isFalse();
    assertThat(c.budgets())
        .isEqualTo(new NoirConfig.Budgets(200, Duration.ofMinutes(60), new BigDecimal("10.00")));
    assertThat(c.reworkRetries()).isEqualTo(3);
    assertThat(c.pollInterval()).isEqualTo(Duration.ofSeconds(60));
    assertThat(c.infraRetries()).isEqualTo(5);
    assertThat(c.limits()).isEqualTo(new NoirConfig.Limits(2.0, "4g", 512));
    assertThat(c.images())
        .isEqualTo(new NoirConfig.Images("noir-builder:0.1.0", "noir-grader:0.1.0"));
    assertThat(c.examiner())
        .isEqualTo(new NoirConfig.Examiner(null, 100, Duration.ofMinutes(30), 3));
    assertThat(c.timeouts())
        .isEqualTo(new NoirConfig.Timeouts(Duration.ofSeconds(10), Duration.ofMinutes(10)));
  }

  @Test
  void partialFileOverlaysDefaults() throws IOException {
    Files.writeString(
        tmp.resolve("config.yaml"),
        """
        repos: [jwcarman/demo]
        qualityBar: { mutationScore: 0.6 }
        budgets: { wallClock: 30m }
        pollInterval: PT2M
        """);
    var c = ConfigLoader.load(tmp.resolve("config.yaml"));
    assertThat(c.repos()).containsExactly("jwcarman/demo");
    assertThat(c.qualityBar()).isEqualTo(new QualityBar(0.90, 0.6, 15));
    assertThat(c.budgets().wallClock()).isEqualTo(Duration.ofMinutes(30));
    assertThat(c.budgets().turns()).isEqualTo(200);
    assertThat(c.pollInterval()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void examinerAndMaxCrapOverlay() throws IOException {
    Files.writeString(
        tmp.resolve("config.yaml"),
        "examiner: { model: claude-opus-5-5, attempts: 2 }\nqualityBar: { maxCrap: 15 }\n");
    var c = ConfigLoader.load(tmp.resolve("config.yaml"));
    assertThat(c.examiner())
        .isEqualTo(new NoirConfig.Examiner("claude-opus-5-5", 100, Duration.ofMinutes(30), 2));
    assertThat(c.qualityBar()).isEqualTo(new QualityBar(0.90, 0.80, 15));
  }

  @Test
  void shorthandDurations() throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "pollInterval: 45s\ntimeouts: { suite: 2h }\n");
    var c = ConfigLoader.load(tmp.resolve("config.yaml"));
    assertThat(c.pollInterval()).isEqualTo(Duration.ofSeconds(45));
    assertThat(c.timeouts().suite()).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void emptyFileGivesDefaults() throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "");
    assertThat(ConfigLoader.load(tmp.resolve("config.yaml"))).isEqualTo(NoirConfig.defaults());
  }

  @Test
  void unknownKeyIsAnError() throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "reworkRetires: 5\n");
    assertThatThrownBy(() -> ConfigLoader.load(tmp.resolve("config.yaml")))
        .hasMessageContaining("reworkRetires");
  }

  @Test
  void badDurationIsAnError() throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "pollInterval: soon\n");
    assertThatThrownBy(() -> ConfigLoader.load(tmp.resolve("config.yaml")))
        .hasMessageContaining("soon");
  }

  @Test
  void mirrorAbsentByDefault() {
    assertThat(ConfigLoader.load(tmp.resolve("none.yaml")).maven().mirrorUrl()).isEmpty();
  }

  @Test
  void bareMavenKeyMeansNoMirror() throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "maven:\n");
    var config = ConfigLoader.load(tmp.resolve("config.yaml"));
    assertThat(config.maven()).isNotNull();
    assertThat(config.maven().mirrorUrl()).isEmpty();
  }

  @Test
  void mirrorIsParsed() throws IOException {
    Files.writeString(
        tmp.resolve("config.yaml"),
        "maven:\n  mirror: http://host.docker.internal:8081/releases\n");
    assertThat(ConfigLoader.load(tmp.resolve("config.yaml")).maven().mirrorUrl())
        .contains("http://host.docker.internal:8081/releases");
  }

  @Test
  void mirrorMustBeHttpOrHttpsUrl() throws IOException {
    for (var bad : new String[] {"ftp://x", "not a url", "http://"}) {
      Files.writeString(tmp.resolve("config.yaml"), "maven:\n  mirror: \"" + bad + "\"\n");
      assertThatThrownBy(() -> ConfigLoader.load(tmp.resolve("config.yaml")))
          .as(bad)
          .hasMessageContaining("maven.mirror must be an http or https URL");
    }
  }

  @Test
  void hiddenTestsCanBeTurnedOn() throws Exception {
    var file = tmp.resolve("config.yaml");
    java.nio.file.Files.writeString(file, "hiddenTests: true\n");
    assertThat(ConfigLoader.load(file).hiddenTests()).isTrue();
  }
}
