package org.jwcarman.noir.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.AgentAuth;
import org.jwcarman.noir.agent.ClaudeContainerAgent;
import org.jwcarman.noir.build.ContainerBuildRunner;
import org.jwcarman.noir.config.ConfigLoader;
import org.jwcarman.noir.deliver.GitBranchPublisher;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.factory.Daemon;
import org.jwcarman.noir.factory.Dispositions;
import org.jwcarman.noir.factory.Factory;
import org.jwcarman.noir.factory.GitBundles;
import org.jwcarman.noir.factory.GitHubDelivery;
import org.jwcarman.noir.factory.Reconciler;
import org.jwcarman.noir.factory.Retry;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.github.GhCli;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.grade.ContainerGradeRunner;
import org.jwcarman.noir.measure.ContainerMeasureRunner;
import org.jwcarman.noir.seal.ContainerSealCheckRunner;
import org.jwcarman.noir.seal.Examiner;
import org.jwcarman.noir.seal.Sealer;
import org.jwcarman.noir.store.Store;

/** Builds the real object graph. */
final class Wiring {
  private Wiring() {}

  /** A seal operation that, when invoked, checks credentials and builds the image first. */
  static SealOperation sealOperation(NoirHome home) {
    return (draftId, repoDir) -> {
      var auth = AgentAuth.fromEnvironment(System.getenv());
      var config = ConfigLoader.load(home.config());
      var runner = new ProcessCommandRunner();
      var docker = new DockerCli(runner, DockerCli.sealLabel(home));
      Images.ensureBuilder(docker, config.images().builder());
      var sealer =
          new Sealer(
              new Store(home),
              new GitCli(runner),
              new GhCli(runner),
              new Examiner(new ClaudeContainerAgent(docker, auth), config),
              new ContainerSealCheckRunner(docker, config),
              config,
              Clock.systemUTC());
      return sealer.seal(draftId, repoDir);
    };
  }

  /**
   * The daemon. When invoked it checks credentials, ensures the builder and grader images, then
   * runs one pass or polls until interrupted. {@link RunCommand} redacts any error it prints.
   */
  static RunOperation runOperation(NoirHome home) {
    return (once, listener) -> {
      var auth = AgentAuth.fromEnvironment(System.getenv());
      var daemon = daemon(home, auth, Set.copyOf(auth.values()), listener);
      if (once) {
        daemon.runOnce();
      } else {
        daemon.runForever();
      }
    };
  }

  private static Daemon daemon(
      NoirHome home, Map<String, String> auth, Set<String> secrets, Daemon.Listener listener) {
    var config = ConfigLoader.load(home.config());
    var runner = new ProcessCommandRunner();
    var runLabel = DockerCli.runLabel(home);
    var docker = new DockerCli(runner, runLabel);
    Images.ensureBuilder(docker, config.images().builder());
    var graderImageId = Images.ensureGrader(docker, config.images().grader());
    var tmp = tempRoot(home);
    var store = new Store(home);
    var gh = new GhCli(runner);
    var clock = Clock.systemUTC();
    Retry.Sleeper sleeper = Wiring::sleep;
    var delivery = new GitHubDelivery(gh, new GitBranchPublisher(runner, tmp));
    var factory =
        new Factory(
            store,
            delivery,
            new ContainerBuildRunner(docker, new ClaudeContainerAgent(docker, auth), config),
            new ContainerMeasureRunner(docker, config),
            new ContainerGradeRunner(docker, config),
            new GitBundles(runner, tmp),
            config,
            graderImageId,
            clock,
            Retry.forConfig(config, sleeper),
            secrets);
    var reconciler = new Reconciler(store, gh, delivery, clock);
    return new Daemon(
        store,
        gh,
        docker,
        runLabel,
        factory,
        reconciler,
        new Dispositions(store, gh, new GitBundles(runner, tmp), clock),
        config,
        sleeper,
        clock,
        secrets,
        listener);
  }

  /** Every credential value in the environment that agents could receive. */
  static Set<String> secrets() {
    return java.util.stream.Stream.of("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN")
        .map(System::getenv)
        .filter(v -> v != null && !v.isBlank())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Dispositions backed by the real {@code gh} and git, for {@code noir status --refresh}. */
  static Dispositions dispositions(NoirHome home) {
    var runner = new ProcessCommandRunner();
    return new Dispositions(
        new Store(home),
        new GhCli(runner),
        new GitBundles(runner, tempRoot(home)),
        Clock.systemUTC());
  }

  static GitHubPort github() {
    return new GhCli(new ProcessCommandRunner());
  }

  /** noir's scratch directory for host-side git work; absolute, as the bundle tools require. */
  private static Path tempRoot(NoirHome home) {
    var tmp = home.root().resolve("tmp").toAbsolutePath();
    try {
      Files.createDirectories(tmp);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create " + tmp, e);
    }
    return tmp;
  }

  /** Sleeps; an interrupt ends the sleep early and stays set, so the daemon loop stops. */
  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
