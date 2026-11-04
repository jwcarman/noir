package org.jwcarman.noir.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import org.jwcarman.noir.NoirHome;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "noir",
        mixinStandardHelpOptions = true,
        version = "noir 0.1.0",
        description = "A dark software factory.")
public final class NoirCli implements Runnable {

    private final NoirHome defaultHome;

    @Option(names = "--home", description = "noir home directory (default: ~/.noir)", scope = CommandLine.ScopeType.INHERIT)
    private Path homeOverride;

    NoirCli(NoirHome defaultHome) {
        this.defaultHome = defaultHome;
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
        var cmd = new CommandLine(new NoirCli(home));
        cmd.setOut(out);
        cmd.setErr(err);
        int code = cmd.execute(args);
        out.flush();
        err.flush();
        return code;
    }

    public static void main(String[] args) {
        System.exit(execute(
                NoirHome.defaultHome(), new PrintWriter(System.out, true), new PrintWriter(System.err, true), args));
    }
}
