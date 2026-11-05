package org.jwcarman.noir.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.render.SpecRenderer;
import org.jwcarman.noir.spec.IssueSpec;
import org.jwcarman.noir.spec.IssueSpecException;
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
        problems = IssueSpec.lint(IssueSpec.parse(useTitle, body));
      } catch (IssueSpecException e) {
        problems = e.problems();
      }
      if (problems.isEmpty()) {
        out.println("OK");
        return 0;
      }
      problems.forEach(out::println);
      return 1;
    }
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
