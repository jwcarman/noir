package org.jwcarman.noir.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitException;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.IssueSpecException;
import org.jwcarman.noir.spec.SpliceException;
import org.jwcarman.noir.spec.StubSplicer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Checks an issue-body spec with the server's own parser, or prints a starting template. */
@Command(
    name = "spec",
    description = "Check or scaffold an issue-body spec.",
    subcommands = {SpecCommand.Check.class, SpecCommand.Template.class})
final class SpecCommand implements Runnable {
  @Spec CommandSpec spec;

  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        spec.commandLine(), "Missing required subcommand");
  }

  private static final Pattern ISSUE_REF = Pattern.compile("([^/#\\s]+/[^/#\\s]+)#(\\d+)");

  @Command(name = "check", description = "Parse and lint a spec; prints OK or every problem.")
  static final class Check implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", description = "File containing the issue body.")
    Path file;

    @Option(names = "--title", description = "Issue title (required with a file).")
    String title;

    @Option(names = "--issue", description = "Check a GitHub issue: owner/repo#number.")
    String issue;

    @Option(
        names = "--repo",
        description = "Also check the spec against origin/HEAD of this local clone.")
    Path repo;

    private List<String> checkAgainstRepo(IssueSpec parsed) {
      return checkAgainstRepoWith(new GitCli(new ProcessCommandRunner()), repo, parsed);
    }

    @Override
    public Integer call() {
      var out = spec.commandLine().getOut();
      var err = spec.commandLine().getErr();
      if ((file == null) == (issue == null)) {
        err.println("Give either a file or --issue, not both and not neither");
        return 2;
      }
      String useTitle;
      String body;
      if (file != null) {
        if (title == null) {
          err.println("--title is required when checking a file");
          return 2;
        }
        try {
          body = Files.readString(file);
        } catch (IOException | RuntimeException e) {
          err.println("Cannot read " + file + ": " + e.getMessage());
          return 1;
        }
        useTitle = title;
      } else {
        var m = ISSUE_REF.matcher(issue);
        if (!m.matches()) {
          err.println("expected owner/repo#number");
          return 2;
        }
        var cli = (NoirCli) spec.root().userObject();
        try {
          var snapshot = cli.github().issue(m.group(1), Integer.parseInt(m.group(2)));
          if (!snapshot.open()) {
            out.println("issue is closed");
            return 1;
          }
          useTitle = snapshot.title();
          body = snapshot.body();
        } catch (RuntimeException e) {
          err.println(Secrets.describe(e, cli.secrets()));
          return 1;
        }
      }
      List<String> problems;
      try {
        var parsed = IssueSpec.parse(useTitle, body);
        problems = new ArrayList<>(IssueSpec.lint(parsed));
        if (repo != null) {
          problems.addAll(checkAgainstRepo(parsed));
        }
      } catch (IssueSpecException e) {
        problems = e.problems();
      } catch (RepoNotReadyException | GitException e) {
        out.println(e.getMessage());
        return 1;
      }
      if (problems.isEmpty()) {
        out.println("OK");
        return 0;
      }
      problems.forEach(out::println);
      return 1;
    }
  }

  private static final class RepoNotReadyException extends RuntimeException {
    RepoNotReadyException(String message) {
      super(message);
    }
  }

  private static List<String> checkAgainstRepoWith(GitPort git, Path repo, IssueSpec parsed) {
    String base;
    try {
      base = git.revParse(repo, "origin/HEAD");
    } catch (GitException e) {
      throw new RepoNotReadyException(
          "origin/HEAD is not set; run `git remote set-head origin --auto`");
    }
    var problems = new ArrayList<String>();
    for (var stub : parsed.stubs()) {
      var path = stub.path();
      if (parsed.modifies().contains(path)) {
        var source = git.show(repo, base, path);
        if (source.isEmpty()) {
          problems.add("modify target " + path + " does not exist on the default branch");
          continue;
        }
        try {
          StubSplicer.splice(path, source.get(), stub.content());
        } catch (SpliceException e) {
          problems.add("modify target " + path + ": " + e.getMessage());
        }
      } else if (git.exists(repo, base, path)) {
        problems.add("API stub " + path + " already exists on the default branch");
      }
    }
    for (var test : parsed.testsToUpdate()) {
      if (!git.exists(repo, base, test)) {
        problems.add("test to update " + test + " does not exist on the default branch");
      }
    }
    return problems;
  }

  @Command(name = "template", description = "Print a starting spec body.")
  static final class Template implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Option(names = "--title", required = true, description = "Issue title.")
    String title;

    @Override
    public Integer call() {
      spec.commandLine().getOut().print(SpecRenderer.template(title));
      return 0;
    }
  }
}
