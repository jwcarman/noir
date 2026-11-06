package org.jwcarman.noir.factory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.commit.Commitments;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.spec.Example;
import org.jwcarman.noir.spec.Requirement;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * A sealed work item in a temporary store, built directly from the Plan 2 seal fixtures (stubs,
 * reference, holdout-good) the way the sealer leaves one: {@code spec.json} with the base SHA of a
 * {@link GitFixtures} origin, {@code base.bundle}, {@code stubs/}, {@code holdout/}, {@code
 * reference/}, {@code commitment.json}, {@code workitem.json} SEALED, a {@code sealed} event, and
 * issue #1 in a {@link FakeGitHub} labelled {@code noir:ready}.
 */
record SealedItemFixture(
    NoirHome home, Store store, FakeGitHub github, Path origin, Spec spec, Path itemDir) {
  static final String REPO = "jwcarman/demo";
  static final String COUNTER_PATH = "src/main/java/com/example/api/Counter.java";

  String id() {
    return spec.id();
  }

  static SealedItemFixture create(Path root) {
    return create(root, List.of());
  }

  /** {@code modifies} lists the existing files the builder is allowed to change. */
  static SealedItemFixture create(Path root, List<String> modifies) {
    var home = new NoirHome(root.resolve("home"));
    var store = new Store(home);
    var github = new FakeGitHub().withNoirLabels("jwcarman/demo");
    var origin = GitFixtures.originWithCommit(root.resolve("remotes"), REPO);
    var clone = GitFixtures.cloneOf(origin, root.resolve("clone"));
    var git = new GitCli(new ProcessCommandRunner());
    var baseSha = git.revParse(clone, "origin/main");

    var spec =
        Specs.valid()
            .withTitle("Counter")
            .withBaseSha(baseSha)
            .withApiStubs(List.of(COUNTER_PATH))
            .withModifies(modifies)
            .withRequirements(
                List.of(
                    new Requirement(
                        "R1",
                        "increment adds one; a new counter reads zero",
                        List.of(new Example("a new counter", "increment()", "get() returns 1"))),
                    new Requirement(
                        "R2",
                        "reset sets the count to zero",
                        List.of(new Example("a counter at 1", "reset()", "get() returns 0")))));

    var itemDir = store.itemDir(spec.id());
    try {
      Files.createDirectories(itemDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    SpecJson.write(spec, itemDir.resolve("spec.json"));
    git.bundle(clone, baseSha, itemDir.resolve("base.bundle"));
    copyTree(sealFixture("stubs"), itemDir.resolve("stubs"));
    copyTree(sealFixture("holdout-good"), itemDir.resolve("holdout"));
    copyTree(sealFixture("reference"), itemDir.resolve("reference"));
    var commitment = Commitments.create(spec, itemDir, "scripted-examiner", "body");
    Commitments.write(commitment, itemDir.resolve("commitment.json"));

    int issue =
        github.createIssue(
            REPO,
            spec.title(),
            SpecRenderer.issueBody(spec, SpecRenderer.loadContract(spec, itemDir)),
            List.of("noir:ready"));
    var item = WorkItem.sealed(spec.id(), REPO, issue);
    store.writeWorkItem(item);
    store.appendEvent(
        Event.of(
            Clock.systemUTC(),
            spec.id(),
            "sealed",
            0,
            Map.of(
                "issue",
                issue,
                "commitmentSha256",
                Commitments.sha256(commitment),
                "baseSha",
                baseSha)));
    return new SealedItemFixture(home, store, github, origin, spec, itemDir);
  }

  static Path sealFixture(String name) {
    return resource("/fixtures/seal/" + name);
  }

  static Path resource(String name) {
    try {
      return Path.of(SealedItemFixture.class.getResource(name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Copies a directory tree, replacing files that already exist. */
  static void copyTree(Path from, Path to) {
    try (var paths = Files.walk(from)) {
      for (var p : paths.toList()) {
        var dest = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
