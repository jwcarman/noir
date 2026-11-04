package org.jwcarman.noir.exec;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A process to run. {@code env} entries are added to the inherited environment. At most {@code
 * outputLimit} bytes of each of stdout and stderr are kept (the tail); the rest is drained and
 * dropped.
 */
public record Command(
    List<String> argv,
    Path cwd,
    Map<String, String> env,
    Duration timeout,
    byte[] stdin,
    long outputLimit) {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
  public static final long DEFAULT_OUTPUT_LIMIT = 16L * 1024 * 1024;

  /** The largest limit a single in-memory buffer can hold. */
  public static final long MAX_OUTPUT_LIMIT = Integer.MAX_VALUE - 16;

  public Command {
    argv = List.copyOf(argv);
    env = env == null ? Map.of() : Map.copyOf(env);
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    if (outputLimit <= 0) {
      outputLimit = DEFAULT_OUTPUT_LIMIT;
    }
    if (outputLimit > MAX_OUTPUT_LIMIT) {
      throw new IllegalArgumentException("outputLimit too large: " + outputLimit);
    }
  }

  public Command(
      List<String> argv, Path cwd, Map<String, String> env, Duration timeout, byte[] stdin) {
    this(argv, cwd, env, timeout, stdin, DEFAULT_OUTPUT_LIMIT);
  }

  public static Command of(String... argv) {
    return new Command(List.of(argv), null, Map.of(), DEFAULT_TIMEOUT, null);
  }

  public static Command of(List<String> argv) {
    return new Command(argv, null, Map.of(), DEFAULT_TIMEOUT, null);
  }

  public Command inDir(Path dir) {
    return new Command(argv, dir, env, timeout, stdin, outputLimit);
  }

  public Command withEnv(Map<String, String> v) {
    return new Command(argv, cwd, v, timeout, stdin, outputLimit);
  }

  public Command withTimeout(Duration v) {
    return new Command(argv, cwd, env, v, stdin, outputLimit);
  }

  public Command withStdin(byte[] v) {
    return new Command(argv, cwd, env, timeout, v, outputLimit);
  }

  public Command withOutputLimit(long v) {
    return new Command(argv, cwd, env, timeout, stdin, v);
  }

  public Command withStdin(String v) {
    return withStdin(v.getBytes(UTF_8));
  }
}
