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
    assertThat(c.qualityBar()).isEqualTo(new QualityBar(0.80, 0.70));
    assertThat(c.sealDiscrimination()).isEqualTo(0.70);
    assertThat(c.budgets())
        .isEqualTo(new NoirConfig.Budgets(200, Duration.ofMinutes(60), new BigDecimal("10.00")));
    assertThat(c.reworkRetries()).isEqualTo(3);
    assertThat(c.pollInterval()).isEqualTo(Duration.ofSeconds(60));
    assertThat(c.infraRetries()).isEqualTo(5);
    assertThat(c.limits()).isEqualTo(new NoirConfig.Limits(2.0, "4g", 512));
    assertThat(c.images()).isEqualTo(new NoirConfig.Images(null, null));
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
    assertThat(c.qualityBar()).isEqualTo(new QualityBar(0.80, 0.6));
    assertThat(c.budgets().wallClock()).isEqualTo(Duration.ofMinutes(30));
    assertThat(c.budgets().turns()).isEqualTo(200);
    assertThat(c.pollInterval()).isEqualTo(Duration.ofMinutes(2));
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
}
