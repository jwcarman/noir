package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecJsonTest {
  @TempDir Path tmp;

  @Test
  void roundTrips() {
    var spec = Specs.valid();
    SpecJson.write(spec, tmp.resolve("spec.json"));
    assertThat(SpecJson.read(tmp.resolve("spec.json"))).isEqualTo(spec);
  }

  @Test
  void rejectsUnknownProperty() throws IOException {
    Files.writeString(tmp.resolve("spec.json"), "{\"schemaVersion\":1,\"bogus\":true}");
    assertThatThrownBy(() -> SpecJson.read(tmp.resolve("spec.json")))
        .isInstanceOf(UncheckedIOException.class);
  }
}
