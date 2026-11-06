package org.jwcarman.noir.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MirroredContainerPortTest {
  static final String BUILDER = "noir-builder:test";
  static final String GRADER = "noir-grader:test";

  @TempDir Path tmp;

  private static ContainerResult ok() {
    return new ContainerResult(0, "", "", false, null);
  }

  @Test
  void builderSpecGainsReadOnlyMountAndMavenArgs() {
    var fake = new FakeContainerPort(s -> ok());
    var settings = tmp.resolve("maven-settings.xml");
    new MirroredContainerPort(fake, BUILDER, settings)
        .run(ContainerSpec.of(BUILDER, "mvn", "-v").withEnv(Map.of("A", "b")));
    var seen = fake.specs().get(0);
    assertThat(seen.mounts()).contains(Mount.ro(settings, MirroredContainerPort.SETTINGS_PATH));
    assertThat(seen.env())
        .containsEntry("MAVEN_ARGS", "-s /opt/noir/maven-settings.xml")
        .containsEntry("A", "b");
  }

  @Test
  void graderSpecIsUntouched() {
    var fake = new FakeContainerPort(s -> ok());
    var spec = ContainerSpec.of(GRADER, "true");
    new MirroredContainerPort(fake, BUILDER, tmp.resolve("s.xml")).run(spec);
    assertThat(fake.specs()).containsExactly(spec);
  }

  @Test
  void withoutMirrorWiringReturnsThePortItself() {
    var fake = new FakeContainerPort(s -> ok());
    assertThat(MirroredContainerPort.wrap(fake, BUILDER, Optional.empty(), tmp)).isSameAs(fake);
  }

  @Test
  void wrapWritesEscapedSettingsFile() throws IOException {
    var fake = new FakeContainerPort(s -> ok());
    var port = MirroredContainerPort.wrap(fake, BUILDER, Optional.of("http://h/r?a=1&b=2"), tmp);
    port.run(ContainerSpec.of(BUILDER, "true"));
    var file = fake.specs().get(0).mounts().get(0).host();
    assertThat(Files.readString(file))
        .isEqualTo(
            """
            <settings>
              <mirrors>
                <mirror>
                  <id>noir-mirror</id>
                  <mirrorOf>central</mirrorOf>
                  <url>http://h/r?a=1&amp;b=2</url>
                </mirror>
              </mirrors>
            </settings>
            """);
  }

  @Test
  void delegatesTheOtherOperations() {
    var fake = new FakeContainerPort(s -> ok());
    var port = new MirroredContainerPort(fake, BUILDER, tmp.resolve("s.xml"));
    assertThat(port.imageId("x")).isPresent();
    port.removeLabelled("a=b");
    assertThat(fake.removedLabels()).containsExactly("a=b");
  }
}
