package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.exec.ToolMissingException;
import org.jwcarman.noir.git.GitException;
import org.jwcarman.noir.github.GitHubException;

class RetryTest {
  final List<Duration> sleeps = new ArrayList<>();
  final Retry retry = new Retry(8, Duration.ofSeconds(2), Duration.ofSeconds(60), sleeps::add);

  @Test
  void backsOffExponentiallyUpToTheCap() {
    int[] calls = {0};
    assertThatThrownBy(() ->
            retry.call(() -> {
              calls[0]++;
              throw new DockerException("down");
            }))
        .isInstanceOf(InfraFailure.class)
        .hasCauseInstanceOf(DockerException.class);
    assertThat(calls[0]).isEqualTo(8);
    assertThat(sleeps)
        .containsExactly(
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16),
            Duration.ofSeconds(32),
            Duration.ofSeconds(60),
            Duration.ofSeconds(60));
  }

  @Test
  void retriesGitHubAndGitFailuresThenSucceeds() {
    int[] calls = {0};
    var result =
        retry.call(() -> {
          calls[0]++;
          if (calls[0] == 1) {
            throw new GitHubException("rate limited");
          }
          if (calls[0] == 2) {
            throw new GitException("fetch failed");
          }
          return "ok";
        });
    assertThat(result).isEqualTo("ok");
    assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
  }

  @Test
  void nonInfraExceptionsPropagateImmediately() {
    var boom = new IllegalArgumentException("bug");
    assertThatThrownBy(() ->
            retry.call(() -> {
              throw boom;
            }))
        .isSameAs(boom);
    var missing = new ToolMissingException("docker", null);
    assertThatThrownBy(() ->
            retry.call(() -> {
              throw missing;
            }))
        .isSameAs(missing);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void forConfigTriesOncePlusInfraRetries() {
    var r = Retry.forConfig(NoirConfig.defaults(), sleeps::add);
    int[] calls = {0};
    assertThatThrownBy(() ->
            r.call(() -> {
              calls[0]++;
              throw new DockerException("down");
            }))
        .isInstanceOf(InfraFailure.class);
    assertThat(calls[0]).isEqualTo(NoirConfig.defaults().infraRetries() + 1);
    assertThat(sleeps).hasSize(NoirConfig.defaults().infraRetries());
  }
}
