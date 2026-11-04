package org.jwcarman.noir.exec;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A process to run. {@code env} entries are added to the inherited environment. */
public record Command(
    List<String> argv, Path cwd, Map<String, String> env, Duration timeout, byte[] stdin) {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

  public Command {
    argv = List.copyOf(argv);
    env = env == null ? Map.of() : Map.copyOf(env);
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
  }

  public static Command of(String... argv) {
    return new Command(List.of(argv), null, Map.of(), DEFAULT_TIMEOUT, null);
  }

  public static Command of(List<String> argv) {
    return new Command(argv, null, Map.of(), DEFAULT_TIMEOUT, null);
  }

  public Command inDir(Path dir) {
    return new Command(argv, dir, env, timeout, stdin);
  }

  public Command withEnv(Map<String, String> v) {
    return new Command(argv, cwd, v, timeout, stdin);
  }

  public Command withTimeout(Duration v) {
    return new Command(argv, cwd, env, v, stdin);
  }

  public Command withStdin(byte[] v) {
    return new Command(argv, cwd, env, timeout, v);
  }

  public Command withStdin(String v) {
    return withStdin(v.getBytes(UTF_8));
  }
}
