package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.FakeContainerPort;
import org.jwcarman.noir.docker.Mount;
import org.jwcarman.noir.spec.Specs;

class ContainerSealCheckRunnerTest {
  @TempDir Path tmp;

  SealInputs inputs() throws Exception {
    for (var d : new String[] {"stubs", "holdout", "reference"}) {
      Files.createDirectories(tmp.resolve(d));
    }
    Files.writeString(tmp.resolve("base.bundle"), "b");
    return SealInputs.of(
        Specs.valid(),
        tmp.resolve("base.bundle"),
        tmp.resolve("stubs"),
        tmp.resolve("holdout"),
        tmp.resolve("reference"));
  }

  @Test
  void networkOnlyForPreparePhaseAndHoldoutNeverOnline() throws Exception {
    var docker = new FakeContainerPort(spec -> new ContainerResult(0, "", "", false, "id"));
    new ContainerSealCheckRunner(docker, NoirConfig.defaults()).run(inputs(), tmp.resolve("out"));
    assertThat(docker.specs()).hasSize(2);
    var prepare = docker.specs().get(0);
    var check = docker.specs().get(1);
    assertThat(prepare.command()).containsExactly("/opt/noir/bin/seal-check.sh", "prepare");
    assertThat(prepare.network()).isTrue();
    assertThat(prepare.mounts())
        .extracting(Mount::container)
        .doesNotContain("/work/holdout", "/work/reference");
    assertThat(check.command()).containsExactly("/opt/noir/bin/seal-check.sh", "check");
    assertThat(check.network()).isFalse();
    assertThat(check.mounts())
        .extracting(Mount::container)
        .contains("/work/holdout", "/work/reference");
  }

  @Test
  void staleOutputIsCleared() throws Exception {
    var out = tmp.resolve("out");
    Files.createDirectories(out.resolve("pit"));
    Files.writeString(out.resolve("pit/mutations.xml"), "<mutations/>");
    var docker = new FakeContainerPort(spec -> new ContainerResult(0, "", "", false, "id"));
    new ContainerSealCheckRunner(docker, NoirConfig.defaults()).run(inputs(), out);
    assertThat(out.resolve("pit/mutations.xml")).doesNotExist();
  }

  @Test
  void timeoutIsRecorded() throws Exception {
    var docker = new FakeContainerPort(spec -> new ContainerResult(-1, "", "", true, "id"));
    var a =
        new ContainerSealCheckRunner(docker, NoirConfig.defaults())
            .run(inputs(), tmp.resolve("out"));
    assertThat(a.read("container.log")).contains("timed out");
  }
}
