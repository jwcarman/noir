package org.jwcarman.noir.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.ProcessCommandRunner;

@Tag("docker")
class DockerProfileSmokeTest {
  @Test
  void dockerDaemonIsReachable() {
    var r = new ProcessCommandRunner().run(Command.of("docker", "version"));
    assertThat(r.ok()).as(r.tail(20)).isTrue();
  }
}
