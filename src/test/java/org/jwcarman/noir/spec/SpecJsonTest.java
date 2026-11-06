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

  @Test
  void readsV1WithEmptyLists() throws IOException {
    var spec = Specs.valid();
    SpecJson.write(spec, tmp.resolve("spec.json"));
    var json = Files.readString(tmp.resolve("spec.json"));
    assertThat(json).contains("\"schemaVersion\" : 1");
    var stripped = json.replaceAll("(?m)^\\s*\"(modifies|testsToUpdate)\" : \\[ \\],?\\n", "");
    Files.writeString(tmp.resolve("v1.json"), stripped.replace(",\n}", "\n}"));
    var read = SpecJson.read(tmp.resolve("v1.json"));
    assertThat(read.modifies()).isEmpty();
    assertThat(read.testsToUpdate()).isEmpty();
    assertThat(read).isEqualTo(spec);
  }

  @Test
  void writesAndReadsV2() {
    var spec = Specs.withModifyAndTests();
    SpecJson.write(spec, tmp.resolve("spec.json"));
    var read = SpecJson.read(tmp.resolve("spec.json"));
    assertThat(read).isEqualTo(spec);
    assertThat(read.schemaVersion()).isEqualTo(2);
    assertThat(read.modifies()).containsExactly("src/main/java/com/example/Old.java");
  }
}
