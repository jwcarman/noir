package org.jwcarman.noir.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.factory.Dispositions;
import org.jwcarman.noir.github.GitHubPort;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "noir",
    mixinStandardHelpOptions = true,
    version = "noir 0.1.0",
    description = "A dark software factory.",
    subcommands = {
      StatusCommand.class,
      DraftCommand.class,
      SealCommand.class,
      RunCommand.class,
      CancelCommand.class,
      ResumeCommand.class,
      AuditCommand.class,
      SkillCommand.class
    })
public final class NoirCli implements Runnable {

  /** The effectful operations behind the commands; tests replace them. */
  record Operations(
      Function<NoirHome, SealOperation> sealers,
      Function<NoirHome, RunOperation> runners,
      Supplier<GitHubPort> github,
      Supplier<Set<String>> secrets,
      Function<NoirHome, Dispositions> dispositions) {
    Operations(
        Function<NoirHome, SealOperation> sealers,
        Function<NoirHome, RunOperation> runners,
        Supplier<GitHubPort> github,
        Supplier<Set<String>> secrets) {
      this(
          sealers,
          runners,
          github,
          secrets,
          home -> {
            throw new IllegalStateException("dispositions not configured");
          });
    }

    static Operations real() {
      return new Operations(
          Wiring::sealOperation,
          Wiring::runOperation,
          Wiring::github,
          Wiring::secrets,
          Wiring::dispositions);
    }
  }

  private final NoirHome defaultHome;
  private final Operations ops;

  @Option(
      names = "--home",
      description = "noir home directory (default: ~/.noir)",
      scope = CommandLine.ScopeType.INHERIT)
  private Path homeOverride;

  NoirCli(NoirHome defaultHome, Operations ops) {
    this.defaultHome = defaultHome;
    this.ops = ops;
  }

  /** The sealing operation for the effective home (real wiring unless a test injects one). */
  SealOperation sealer() {
    return ops.sealers().apply(home());
  }

  /** The daemon for the effective home. */
  RunOperation runner() {
    return ops.runners().apply(home());
  }

  /** Dispositions for the effective home (real gh and git unless a test injects them). */
  Dispositions dispositions() {
    return ops.dispositions().apply(home());
  }

  GitHubPort github() {
    return ops.github().get();
  }

  /** Credential values that must never be printed. */
  Set<String> secrets() {
    return ops.secrets().get();
  }

  /** The effective noir home: {@code --home} if given, else the default. */
  public NoirHome home() {
    return homeOverride != null ? new NoirHome(homeOverride) : defaultHome;
  }

  @Override
  public void run() {
    throw new CommandLine.ParameterException(new CommandLine(this), "Missing required subcommand");
  }

  public static int execute(NoirHome home, PrintWriter out, PrintWriter err, String... args) {
    return execute(home, out, err, Operations.real(), args);
  }

  static int execute(
      NoirHome home,
      PrintWriter out,
      PrintWriter err,
      Function<NoirHome, SealOperation> sealers,
      String... args) {
    var real = Operations.real();
    return execute(
        home,
        out,
        err,
        new Operations(sealers, real.runners(), real.github(), real.secrets(), real.dispositions()),
        args);
  }

  static int execute(
      NoirHome home, PrintWriter out, PrintWriter err, Operations ops, String... args) {
    var cmd = new CommandLine(new NoirCli(home, ops));
    cmd.setOut(out);
    cmd.setErr(err);
    int code = cmd.execute(args);
    out.flush();
    err.flush();
    return code;
  }

  public static void main(String[] args) {
    System.exit(
        execute(
            NoirHome.defaultHome(),
            new PrintWriter(System.out, true),
            new PrintWriter(System.err, true),
            args));
  }
}
