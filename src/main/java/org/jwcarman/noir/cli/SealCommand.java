package org.jwcarman.noir.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.exec.ToolMissingException;
import org.jwcarman.noir.git.GitException;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.seal.SealException;
import org.jwcarman.noir.seal.SealResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "seal",
    description = "Seal a draft: write holdout tests, verify them, open the issue.")
final class SealCommand implements Callable<Integer> {
  @ParentCommand NoirCli parent;
  @picocli.CommandLine.Spec CommandSpec spec;

  @Parameters(index = "0", description = "Draft id.")
  String draftId;

  @Option(names = "--repo-dir", description = "Local clone of the target repository (default: .).")
  Path repoDir = Path.of("");

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    var err = spec.commandLine().getErr();
    try {
      var result = parent.sealer().seal(draftId, repoDir.toAbsolutePath().normalize());
      switch (result) {
        case SealResult.Sealed s ->
            out.printf(
                "Sealed %s as %s#%d (commitment %s)%n",
                s.id(), s.repo(), s.issueNumber(), s.commitmentSha256().substring(0, 12));
        case SealResult.Rejected r -> {
          out.println("Not sealed:");
          r.problems().forEach(p -> out.println("- " + p));
        }
      }
      out.flush();
      return result instanceof SealResult.Sealed ? 0 : 1;
    } catch (ToolMissingException
        | DockerException
        | GitHubException
        | GitException
        | SealException
        | IllegalStateException e) {
      err.println(e.getMessage());
      err.flush();
      return 2;
    }
  }
}
