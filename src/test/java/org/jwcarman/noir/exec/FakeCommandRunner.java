package org.jwcarman.noir.exec;

import java.util.ArrayList;
import java.util.List;

/** Records commands and returns queued results matched by argv prefix. */
public final class FakeCommandRunner implements CommandRunner {
  private record Rule(List<String> prefix, CommandResult result) {}

  private final List<Rule> rules = new ArrayList<>();
  private final List<Command> commands = new ArrayList<>();
  private java.util.function.Consumer<Command> onRun = c -> {};

  /** Called with each command at the moment it runs (e.g. to inspect temp files it references). */
  public FakeCommandRunner onRun(java.util.function.Consumer<Command> hook) {
    this.onRun = hook;
    return this;
  }

  public FakeCommandRunner whenArgvStartsWith(List<String> prefix, CommandResult result) {
    rules.add(new Rule(List.copyOf(prefix), result));
    return this;
  }

  public List<Command> commands() {
    return commands;
  }

  @Override
  public CommandResult run(Command command) {
    commands.add(command);
    onRun.accept(command);
    for (int i = rules.size() - 1; i >= 0; i--) {
      var rule = rules.get(i);
      var argv = command.argv();
      if (argv.size() >= rule.prefix().size()
          && argv.subList(0, rule.prefix().size()).equals(rule.prefix())) {
        return rule.result();
      }
    }
    throw new AssertionError("Unexpected command: " + command.argv());
  }
}
