package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.ContainerResult;
import org.jwcarman.noir.docker.DockerException;
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
        .doesNotContain("/work/holdout", "/work/reference")
        .contains("/work/updated-tests");
    assertThat(check.command()).containsExactly("/opt/noir/bin/seal-check.sh", "check");
    assertThat(check.network()).isFalse();
    assertThat(check.mounts())
        .extracting(Mount::container)
        .contains("/work/holdout", "/work/reference");
  }

  /** The host paths mounted at /work/updated-tests, and what each held while the phase ran. */
  record Staged(Path host, List<String> files) {}

  /**
   * Only the listed tests the reference contains are staged for prepare, beside the output
   * directory (never inside it, never in the system temp directory); the staging is gone once
   * prepare ends, and the check phase does not see it.
   */
  @Test
  void prepareSeesOnlyTheListedUpdatedTests() throws Exception {
    var in = inputs();
    var spec = Specs.withModifyAndTests();
    in =
        new SealInputs(
            spec,
            in.bundle(),
            in.stubsDir(),
            in.holdoutDir(),
            in.referenceDir(),
            in.targetClasses());
    var reference = in.referenceDir();
    for (var path :
        List.of(
            "src/test/java/com/example/OldTest.java",
            "src/test/java/com/example/UnlistedTest.java",
            "src/main/java/com/example/Old.java")) {
      Files.createDirectories(reference.resolve(path).getParent());
      Files.writeString(reference.resolve(path), "class X {}\n");
    }
    var staged = new java.util.ArrayList<Staged>();
    var docker =
        new FakeContainerPort(s -> {
          s.mounts().stream()
              .filter(m -> m.container().equals("/work/updated-tests"))
              .forEach(m -> {
                try (var walk = Files.walk(m.host())) {
                  staged.add(
                      new Staged(
                          m.host(),
                          walk.filter(Files::isRegularFile)
                              .map(f -> m.host().relativize(f).toString())
                              .sorted()
                              .toList()));
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
          return new ContainerResult(0, "", "", false, "id");
        });
    var out = tmp.resolve("attempt/checks");

    new ContainerSealCheckRunner(docker, NoirConfig.defaults()).run(in, out);

    assertThat(staged).hasSize(1);
    assertThat(staged.getFirst().files()).containsExactly("src/test/java/com/example/OldTest.java");
    var host = staged.getFirst().host();
    assertThat(host.getParent()).isEqualTo(out.getParent());
    assertThat(host).doesNotExist();
    assertThat(docker.specs().get(1).mounts())
        .extracting(Mount::container)
        .doesNotContain("/work/updated-tests");
  }

  @Test
  void stagedUpdatedTestsAreRemovedWhenPrepareFails() throws Exception {
    var in = inputs();
    var staged = new java.util.ArrayList<Path>();
    var docker =
        new FakeContainerPort(s -> {
          s.mounts().stream()
              .filter(m -> m.container().equals("/work/updated-tests"))
              .forEach(m -> staged.add(m.host()));
          throw new DockerException("docker run failed");
        });
    var out = tmp.resolve("attempt/checks");
    assertThatThrownBy(
            () -> new ContainerSealCheckRunner(docker, NoirConfig.defaults()).run(in, out))
        .isInstanceOf(DockerException.class);
    assertThat(staged).hasSize(1);
    assertThat(staged.getFirst()).doesNotExist();
    try (var left = Files.list(out.getParent())) {
      assertThat(left.map(p -> p.getFileName().toString())).containsExactly("checks");
    }
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

  @Test
  void existingTestsRunOnlyForModifySpecsAndExcludeTestsToUpdate() throws Exception {
    var docker = new FakeContainerPort(spec -> new ContainerResult(0, "", "", false, "id"));
    var runner = new ContainerSealCheckRunner(docker, NoirConfig.defaults());
    runner.run(inputs(), tmp.resolve("out"));
    assertThat(docker.specs().get(0).env())
        .containsEntry("EXISTING_TESTS", "no")
        .containsEntry("EXCLUDE_TESTS", "");

    var modify = inputs();
    modify =
        new SealInputs(
            Specs.withModifyAndTests(),
            modify.bundle(),
            modify.stubsDir(),
            modify.holdoutDir(),
            modify.referenceDir(),
            modify.targetClasses());
    runner.run(modify, tmp.resolve("out"));
    var spec = Specs.withModifyAndTests();
    for (var s : docker.specs().subList(2, 4)) {
      assertThat(s.env())
          .containsEntry("EXISTING_TESTS", "yes")
          .containsEntry("EXCLUDE_TESTS", String.join(",", spec.testsToUpdate()));
    }
    assertThat(spec.testsToUpdate()).hasSizeGreaterThan(1);
  }

  /** Answers the inventory phase as {@code seal-check.sh} would, writing into /work/out. */
  static ContainerResult inventoryWritten(org.jwcarman.noir.docker.ContainerSpec spec) {
    var out =
        spec.mounts().stream()
            .filter(m -> m.container().equals("/work/out"))
            .findFirst()
            .orElseThrow()
            .host();
    try {
      Files.writeString(out.resolve("clone.exit"), "0\n");
      Files.writeString(out.resolve("inventory.exit"), "0\n");
      Files.writeString(out.resolve("libraries.txt"), "org.assertj:assertj-core:3.27.3\n");
      Files.writeString(out.resolve("packages.txt"), "org.assertj.core.api\n");
      Files.createDirectories(out.resolve("repo"));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return new ContainerResult(0, "", "", false, "id");
  }

  @Test
  void inventoryRunsOnlineAgainstTheBaseOnly() throws Exception {
    var docker = new FakeContainerPort(ContainerSealCheckRunnerTest::inventoryWritten);
    var out = tmp.resolve("inventory");
    Files.createDirectories(out);
    Files.writeString(out.resolve("stale.txt"), "x");
    Files.writeString(tmp.resolve("base.bundle"), "b");

    var files =
        new ContainerSealCheckRunner(docker, NoirConfig.defaults())
            .inventory(tmp.resolve("base.bundle"), "abc123", out);

    assertThat(files)
        .isEqualTo(
            new RepoInventoryFiles(
                List.of("org.assertj:assertj-core:3.27.3"), List.of("org.assertj.core.api")));
    assertThat(docker.specs()).hasSize(1);
    var spec = docker.specs().getFirst();
    assertThat(spec.command()).containsExactly("/opt/noir/bin/seal-check.sh", "inventory");
    assertThat(spec.network()).isTrue();
    assertThat(spec.env()).containsEntry("BASE_SHA", "abc123");
    assertThat(spec.mounts())
        .extracting(Mount::container)
        .containsExactlyInAnyOrder("/work/base.bundle", "/work/out");
    assertThat(out.resolve("stale.txt")).doesNotExist();
    assertThat(out.resolve("repo")).doesNotExist();
    assertThat(out.resolve("container.log")).exists();
  }

  /** Answers the inventory phase, then overwrites {@code file} with {@code content}. */
  static FakeContainerPort inventoryWith(String file, String content, boolean timedOut) {
    return new FakeContainerPort(spec -> {
      inventoryWritten(spec);
      var out = spec.mounts().getLast().host();
      try {
        if (content == null) {
          Files.delete(out.resolve(file));
        } else {
          Files.writeString(out.resolve(file), content);
        }
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return new ContainerResult(timedOut ? -1 : 0, "", "", timedOut, "id");
    });
  }

  RepoInventoryFiles inventory(FakeContainerPort docker) throws Exception {
    Files.writeString(tmp.resolve("base.bundle"), "b");
    return new ContainerSealCheckRunner(docker, NoirConfig.defaults())
        .inventory(tmp.resolve("base.bundle"), "abc123", tmp.resolve("inventory"));
  }

  /**
   * A repository the seal checks reject anyway (no pom at its root, or a multi-module pom) has an
   * empty inventory: the script says why in inventory-empty.txt and lists nothing.
   */
  @Test
  void repositoryTheChecksRejectHasAnEmptyInventory() throws Exception {
    for (var reason :
        new String[] {"no pom.xml at the repository root\n", "the pom declares <modules>\n"}) {
      var docker =
          new FakeContainerPort(spec -> {
            inventoryWritten(spec);
            var out = spec.mounts().getLast().host();
            try {
              Files.writeString(out.resolve("libraries.txt"), "");
              Files.writeString(out.resolve("packages.txt"), "");
              Files.writeString(out.resolve("inventory-empty.txt"), reason);
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
            return new ContainerResult(0, "", "", false, "id");
          });
      assertThat(inventory(docker))
          .as(reason)
          .isEqualTo(new RepoInventoryFiles(List.of(), List.of()));
      assertThat(tmp.resolve("inventory/inventory-empty.txt")).hasContent(reason.strip());
    }
  }

  /** The marker wins over anything else left in the listing files. */
  @Test
  void emptyInventoryMarkerIgnoresStrayListings() throws Exception {
    var docker = inventoryWith("inventory-empty.txt", "the pom declares <modules>\n", false);
    assertThat(inventory(docker)).isEqualTo(new RepoInventoryFiles(List.of(), List.of()));
    assertThat(tmp.resolve("inventory/libraries.txt")).isEmptyFile();
    assertThat(tmp.resolve("inventory/packages.txt")).isEmptyFile();
  }

  /**
   * Any other failed listing (Maven could not reach a repository, a dependency did not resolve) is
   * an infrastructure failure: the seal is retried, then blocked, never sealed on an empty
   * inventory.
   */
  @Test
  void failedListingIsAnInfrastructureFailure() throws Exception {
    var docker = inventoryWith("inventory.exit", "1\n", false);
    assertThatThrownBy(() -> inventory(docker))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("listing failed");
  }

  @Test
  void failedCloneIsAnInfrastructureFailure() throws Exception {
    var docker = inventoryWith("clone.exit", "128\n", false);
    assertThatThrownBy(() -> inventory(docker))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("clone");
  }

  @Test
  void containerThatNeverFinishedTheListingIsAnInfrastructureFailure() throws Exception {
    var docker = inventoryWith("inventory.exit", null, false);
    assertThatThrownBy(() -> inventory(docker)).isInstanceOf(DockerException.class);
  }

  @Test
  void timedOutInventoryIsAnInfrastructureFailure() throws Exception {
    var docker = inventoryWith("inventory.exit", "1\n", true);
    assertThatThrownBy(() -> inventory(docker))
        .isInstanceOf(DockerException.class)
        .hasMessageContaining("timed out");
  }
}
