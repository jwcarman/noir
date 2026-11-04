package org.jwcarman.noir.factory;

import java.time.Duration;
import java.util.function.Supplier;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.git.GitException;
import org.jwcarman.noir.github.GitHubException;

/**
 * Retries infrastructure failures ({@link DockerException}, {@link GitHubException}, {@link
 * GitException}) with exponential backoff. After {@code maxTries} failed tries it throws {@link
 * InfraFailure}. Every other exception, including {@code ToolMissingException} (a missing tool will
 * not appear by waiting), propagates immediately.
 */
public final class Retry {
  /** Waits between tries; tests record instead of sleeping. */
  public interface Sleeper {
    void sleep(Duration d);
  }

  static final Duration INITIAL = Duration.ofSeconds(2);
  static final Duration CAP = Duration.ofSeconds(60);

  private final int maxTries;
  private final Duration initial;
  private final Duration cap;
  private final Sleeper sleeper;

  public Retry(int maxTries, Duration initial, Duration cap, Sleeper sleeper) {
    if (maxTries < 1) {
      throw new IllegalArgumentException("maxTries must be at least 1");
    }
    this.maxTries = maxTries;
    this.initial = initial;
    this.cap = cap;
    this.sleeper = sleeper;
  }

  /** One try plus {@link NoirConfig#infraRetries()} retries, backing off 2 s doubling to 60 s. */
  public static Retry forConfig(NoirConfig config, Sleeper sleeper) {
    return new Retry(config.infraRetries() + 1, INITIAL, CAP, sleeper);
  }

  public <T> T call(Supplier<T> action) {
    var delay = initial;
    for (int tryNo = 1; ; tryNo++) {
      try {
        return action.get();
      } catch (DockerException | GitHubException | GitException e) {
        if (tryNo >= maxTries) {
          throw new InfraFailure(e);
        }
      }
      sleeper.sleep(delay);
      delay = delay.multipliedBy(2);
      if (delay.compareTo(cap) > 0) {
        delay = cap;
      }
    }
  }

  /** {@link #call(Supplier)} for an action with no result. */
  public void run(Runnable action) {
    call(() -> {
      action.run();
      return null;
    });
  }
}
