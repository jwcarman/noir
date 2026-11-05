package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.deliver.FakeBranchPublisher;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.WorkItem;

class GitHubDeliveryTest {
  final FakeGitHub gh = new FakeGitHub().withDefaultBranch("trunk");
  final FakeBranchPublisher publisher = new FakeBranchPublisher();
  final GitHubDelivery delivery = new GitHubDelivery(gh, publisher);
  final AttemptPaths attempt = new AttemptPaths(Path.of("/store/item"), 2);
  WorkItem item;

  @BeforeEach
  void issue() {
    int n = gh.createIssue("jwcarman/demo", "t", "b", List.of("noir:ready"));
    item = WorkItem.sealed(Specs.ID, "jwcarman/demo", n);
  }

  @Test
  void pushesTheAttemptBundleAndCreatesAPullRequest() {
    int pr = delivery.openPullRequest(item, Specs.valid(), attempt, "body", true);
    assertThat(publisher.pushes())
        .containsExactly(
            new FakeBranchPublisher.Push(
                "jwcarman/demo",
                Path.of("/store/item/attempts/2/build/out.bundle"),
                "noir/1-add-a-bounded-cache"));
    assertThat(gh.pullRequests())
        .containsExactly(
            new FakeGitHub.Pr(
                pr, "trunk", "noir/1-add-a-bounded-cache", "Add a bounded cache", "body", true));
  }

  @Test
  void reusesAnExistingPullRequest() {
    int first = delivery.openPullRequest(item, Specs.valid(), attempt, "body", false);
    int second = delivery.openPullRequest(item, Specs.valid(), attempt, "body", false);
    assertThat(second).isEqualTo(first);
    assertThat(gh.pullRequests()).hasSize(1);
    assertThat(publisher.pushes()).hasSize(2);
  }

  @Test
  void labelMirrorsTheState() {
    delivery.label(item, State.BUILDING);
    assertThat(gh.issue(1).labels()).containsExactlyInAnyOrder("noir:ready", "noir:building");
    delivery.label(item, State.CANCELLED);
    assertThat(gh.issue(1).labels()).containsExactly("noir:ready");
  }

  @Test
  void commentsOnTheIssue() {
    delivery.comment(item, "hello");
    assertThat(gh.issue(1).comments()).containsExactly("hello");
  }

  @Test
  void attemptPathsLayout() {
    assertThat(attempt.dir()).isEqualTo(Path.of("/store/item/attempts/2"));
    assertThat(attempt.build()).isEqualTo(Path.of("/store/item/attempts/2/build"));
    assertThat(attempt.measure()).isEqualTo(Path.of("/store/item/attempts/2/measure"));
    assertThat(attempt.grade()).isEqualTo(Path.of("/store/item/attempts/2/grade"));
    assertThat(attempt.feedback()).isEqualTo(Path.of("/store/item/attempts/2/FEEDBACK.md"));
  }
}
