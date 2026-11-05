package org.jwcarman.noir.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FakeGitHubTest {
  static final String REPO = "jwcarman/demo";

  @Test
  void undefinedLabelsAreRejectedLikeGitHub() {
    var gh = new FakeGitHub();

    assertThatThrownBy(() -> gh.createIssue(REPO, "t", "b", List.of(NoirLabels.READY)))
        .isInstanceOf(GitHubException.class)
        .hasMessage("label not found: noir:ready");
    int n = gh.createIssue(REPO, "t", "b", List.of());
    assertThatThrownBy(() -> gh.addLabel(n, NoirLabels.READY, "alice", Instant.EPOCH))
        .isInstanceOf(GitHubException.class)
        .hasMessage("label not found: noir:ready");
    assertThatThrownBy(() -> gh.setNoirLabel(REPO, n, Optional.of("noir:sealing")))
        .isInstanceOf(GitHubException.class)
        .hasMessageStartingWith("label not found: noir:");
    assertThatThrownBy(() -> gh.removeLabel(REPO, n, NoirLabels.READY))
        .isInstanceOf(GitHubException.class)
        .hasMessage("label not found: noir:ready");
  }

  @Test
  void definedLabelsWork() {
    var gh = new FakeGitHub();
    gh.ensureLabels(REPO);
    gh.defineLabels(REPO, "bug");

    int n = gh.createIssue(REPO, "t", "b", List.of("bug"));
    gh.addLabel(n, NoirLabels.READY, "alice", Instant.EPOCH);
    gh.setNoirLabel(REPO, n, Optional.of("noir:sealing"));
    gh.removeLabel(REPO, n, "noir:draft"); // defined but absent: a no-op

    assertThat(gh.labels(n)).containsExactly("bug", "noir:ready", "noir:sealing");
    assertThat(gh.ensuredRepos()).containsExactly(REPO);
  }

  @Test
  void labelsAreDefinedPerRepo() {
    var gh = new FakeGitHub();
    gh.ensureLabels(REPO);

    assertThatThrownBy(() -> gh.createIssue("jwcarman/other", "t", "b", List.of("noir:ready")))
        .isInstanceOf(GitHubException.class);
  }
}
