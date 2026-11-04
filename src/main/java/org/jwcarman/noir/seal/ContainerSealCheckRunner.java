package org.jwcarman.noir.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerPort;
import org.jwcarman.noir.docker.ContainerSpec;
import org.jwcarman.noir.docker.Mount;
import org.jwcarman.noir.spec.ItemId;

/** Runs {@code seal-check.sh} in the builder image. */
public final class ContainerSealCheckRunner implements SealCheckRunner {
  static final int RERUNS = 3;

  private final ContainerPort docker;
  private final NoirConfig config;

  public ContainerSealCheckRunner(ContainerPort docker, NoirConfig config) {
    this.docker = docker;
    this.config = config;
  }

  @Override
  public SealArtifacts run(SealInputs in, Path outDir) {
    try {
      Files.createDirectories(outDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var spec =
        ContainerSpec.of(config.images().builder(), "/opt/noir/bin/seal-check.sh")
            .withMounts(
                List.of(
                    Mount.ro(in.bundle(), "/work/base.bundle"),
                    Mount.ro(in.stubsDir(), "/work/stubs"),
                    Mount.ro(in.holdoutDir(), "/work/holdout"),
                    Mount.ro(in.referenceDir(), "/work/reference"),
                    Mount.rw(outDir, "/work/out")))
            .withEnv(
                Map.of(
                    "BASE_SHA", in.spec().baseSha(),
                    "HOLDOUT_PKG", ItemId.holdoutPackage(in.spec().id()),
                    "TARGET_CLASSES", String.join(",", in.targetClasses()),
                    "TEST_TIMEOUT", config.timeouts().perTest().toSeconds() + "s",
                    "RERUNS", String.valueOf(RERUNS)))
            .withNetwork(true)
            .withLimits(config.limits())
            .withTimeout(Duration.ofMinutes(30));
    var result = docker.run(spec);
    try {
      Files.writeString(outDir.resolve("container.log"), result.stdout() + result.stderr());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new SealArtifacts(outDir);
  }
}
