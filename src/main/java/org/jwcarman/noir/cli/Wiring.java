package org.jwcarman.noir.cli;

import java.time.Clock;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.AgentAuth;
import org.jwcarman.noir.agent.ClaudeContainerAgent;
import org.jwcarman.noir.config.ConfigLoader;
import org.jwcarman.noir.docker.DockerCli;
import org.jwcarman.noir.docker.Images;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.github.GhCli;
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
      var docker = new DockerCli(runner);
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
}
