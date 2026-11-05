package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.noir.git.GitFixtures.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitFixtures;
import org.jwcarman.noir.github.FakeGitHub;
import org.jwcarman.noir.github.GitHubException;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class SealerTest {
  static final String ID = Specs.ID;
  static final String GOOD_TEST =
      "package "
          + ItemId.holdoutPackage(ID)
          + ";\nimport org.junit.jupiter.api.*;\nclass T {\n"
          + "  @Test @Tag(\"R1\") void a() {}\n  @Test @Tag(\"R2\") void b() {}\n}\n";
  static final String UNTAGGED_TEST =
      "package "
          + ItemId.holdoutPackage(ID)
          + ";\nimport org.junit.jupiter.api.*;\n"
          + "class T { @Test void a() {} }\n";

  @TempDir Path tmp;
  Store store;
  FakeGitHub github;
  GitCli gitCli;
  Path clone;
  Deque<String> holdoutSources;
  Deque<BiFunction<SealInputs, Path, SealArtifacts>> checkResults;
  List<SealInputs> checkCalls;
  ScriptedAgent agent;
  Sealer sealer;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(tmp.resolve("home")));
    github = new FakeGitHub();
    gitCli = new GitCli(new ProcessCommandRunner());
    clone =
        GitFixtures.cloneOf(
            GitFixtures.originWithCommit(tmp.resolve("remotes"), "jwcarman/demo"),
            tmp.resolve("clone"));
    holdoutSources = new ArrayDeque<>();
    checkResults = new ArrayDeque<>();
    checkCalls = new java.util.ArrayList<>();
    agent =
        new ScriptedAgent((n, work) -> {
          var src = holdoutSources.isEmpty() ? GOOD_TEST : holdoutSources.pop();
          write(work.resolve("out/holdout/noir/holdout/T.java"), src);
          write(
              work.resolve("out/reference/src/main/java/com/example/api/Foo.java"),
              "package com.example.api;\npublic interface Foo {}\n");
        });
    SealCheckRunner checks =
        (inputs, out) -> {
          checkCalls.add(inputs);
          return checkResults.isEmpty()
              ? SealArtifactsFixture.good(out)
              : checkResults.pop().apply(inputs, out);
        };
    var clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
    sealer =
        new Sealer(
            store,
            gitCli,
            github,
            new Examiner(agent, NoirConfig.defaults()),
            checks,
            NoirConfig.defaults(),
            clock);
    writeDraft(Specs.valid());
  }

  void writeDraft(Spec spec) {
    SpecJson.write(spec, store.draftDir(ID).resolve("spec.json"));
    write(
        store.draftDir(ID).resolve("stubs/src/main/java/com/example/api/Foo.java"),
        "package com.example.api;\n\n/** A foo. */\npublic interface Foo {}\n");
  }

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void sealsAndMovesDraftIntoStore() {
    var r = (SealResult.Sealed) sealer.seal(ID, clone);
    assertThat(store.readWorkItem(ID))
        .isEqualTo(WorkItem.sealed(ID, "jwcarman/demo", r.issueNumber()));
    assertThat(Files.exists(store.draftDir(ID))).isFalse();
    var issue = github.issue(r.issueNumber());
    assertThat(issue.repo()).isEqualTo("jwcarman/demo");
    assertThat(issue.title()).isEqualTo("Add a bounded cache");
    assertThat(issue.labels()).containsExactly("noir:ready");
    assertThat(issue.body()).endsWith(org.jwcarman.noir.render.SpecRenderer.FOOTER);
    assertThat(issue.comments()).singleElement().asString().contains(r.commitmentSha256());
    assertThat(github.ensuredRepos()).containsExactly("jwcarman/demo");
    var item = store.itemDir(ID);
    assertThat(SpecJson.read(item.resolve("spec.json")).baseSha())
        .isEqualTo(gitCli.revParse(clone, "origin/main"));
    assertThat(item.resolve("base.bundle")).exists();
    assertThat(item.resolve("commitment.json")).exists();
    assertThat(item.resolve("holdout/noir/holdout/T.java")).exists();
    assertThat(store.readEvents(ID))
        .extracting(Event::type)
        .containsExactly("seal_requested", "examiner_attempt", "sealed");
    assertThat(store.readEvents(ID).get(1).data()).containsEntry("ok", true);
  }

  @Test
  void sealRecordsSealRequestedOnce() {
    writeDraft(Specs.valid().withTitle(" "));
    sealer.seal(ID, clone);
    sealer.seal(ID, clone);
    assertThat(store.readEvents(store.draftDir(ID)))
        .extracting(Event::type)
        .containsOnlyOnce("seal_requested");
  }

  @Test
  void lintFailureRejectsWithoutExaminer() {
    writeDraft(Specs.valid().withTitle(" "));
    assertThat(sealer.seal(ID, clone))
        .isEqualTo(new SealResult.Rejected(List.of("title must not be blank")));
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void retriesFeedProblemsToNextAttempt() {
    checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    var r = sealer.seal(ID, clone);
    assertThat(r).isInstanceOf(SealResult.Sealed.class);
    assertThat(agent.requests()).hasSize(2);
    assertThat(agent.requests().get(1).prompt())
        .contains("holdout test passes against the stubs (vacuous)");
    assertThat(store.readEvents(ID))
        .extracting(Event::type)
        .containsExactly("seal_requested", "examiner_attempt", "examiner_attempt", "sealed");
  }

  @Test
  void threeFailuresReject() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    }
    var r = (SealResult.Rejected) sealer.seal(ID, clone);
    assertThat(r.problems())
        .singleElement()
        .asString()
        .startsWith("holdout test passes against the stubs");
    assertThat(agent.requests()).hasSize(3);
    assertThat(Files.exists(store.draftDir(ID))).isTrue();
    assertThat(github.issues()).isEmpty();
  }

  @Test
  void staticAnalysisProblemsSkipContainerChecks() {
    holdoutSources.add(UNTAGGED_TEST);
    var r = sealer.seal(ID, clone);
    assertThat(r).isInstanceOf(SealResult.Sealed.class);
    assertThat(agent.requests()).hasSize(2);
    assertThat(checkCalls).hasSize(1);
    assertThat(agent.requests().get(1).prompt()).contains("test has no requirement tag");
  }

  @Test
  void resumeAfterCrashDoesNotDuplicateIssue() {
    github.failNextComment();
    assertThatThrownBy(() -> sealer.seal(ID, clone)).isInstanceOf(GitHubException.class);
    var r = (SealResult.Sealed) sealer.seal(ID, clone);
    assertThat(github.issues()).hasSize(1);
    assertThat(r.issueNumber()).isEqualTo(github.issues().getFirst().number());
    assertThat(github.issues().getFirst().comments()).hasSize(1);
    assertThat(agent.requests()).as("suite is frozen once the issue exists").hasSize(1);
    assertThat(github.issues().getFirst().comments().getFirst()).contains(r.commitmentSha256());
    var sealed =
        org.jwcarman.noir.commit.Commitments.read(store.itemDir(ID).resolve("commitment.json"));
    assertThat(org.jwcarman.noir.commit.Commitments.sha256(sealed)).isEqualTo(r.commitmentSha256());
  }

  @Test
  void wrongRepositoryRejected() {
    writeDraft(Specs.valid().withRepo("jwcarman/other"));
    var r = (SealResult.Rejected) sealer.seal(ID, clone);
    assertThat(r.problems())
        .singleElement()
        .asString()
        .contains("jwcarman/demo")
        .contains("jwcarman/other");
    assertThat(agent.requests()).isEmpty();
  }

  @Test
  void pinsRemoteDefaultBranchEvenWithLocalCommits() throws IOException {
    var remoteSha = gitCli.revParse(clone, "origin/main");
    Files.writeString(clone.resolve("local.txt"), "x");
    git(clone, "add", ".");
    git(clone, "commit", "-qm", "local");
    sealer.seal(ID, clone);
    assertThat(SpecJson.read(store.itemDir(ID).resolve("spec.json")).baseSha())
        .isEqualTo(remoteSha);
    assertThat(checkCalls.getFirst().spec().baseSha()).isEqualTo(remoteSha);
  }

  @Test
  void agentFailureIsInfrastructureNotTestProblems() {
    var failing =
        new org.jwcarman.noir.agent.AgentPort() {
          int calls;

          @Override
          public org.jwcarman.noir.agent.AgentResult run(org.jwcarman.noir.agent.AgentRequest r) {
            calls++;
            return new org.jwcarman.noir.agent.AgentResult(
                0,
                true,
                false,
                java.math.BigDecimal.ZERO,
                1,
                "default",
                "Invalid API key · Fix external API key",
                "sha256:img");
          }
        };
    var s =
        new Sealer(
            store,
            gitCli,
            github,
            new Examiner(failing, NoirConfig.defaults()),
            (in, out) -> SealArtifactsFixture.good(out),
            NoirConfig.defaults(),
            Clock.systemUTC());
    assertThatThrownBy(() -> s.seal(ID, clone))
        .isInstanceOf(ExaminerFailedException.class)
        .hasMessage("Examiner agent failed: Invalid API key · Fix external API key");
    assertThat(failing.calls).isEqualTo(1);
    assertThat(store.draftDir(ID).resolve("examiner/attempt-1/agent.log"))
        .content()
        .contains("Invalid API key");
    assertThat(github.issues()).isEmpty();
  }

  @Test
  void attemptEventRecordsModelAndImage() {
    sealer.seal(ID, clone);
    assertThat(store.readEvents(ID).get(1).data())
        .containsEntry("model", "scripted")
        .containsEntry("imageId", "scripted");
  }

  @Test
  void missingDraft() {
    assertThatThrownBy(() -> sealer.seal("nr-01JBX4ZQ8K3V0M7N2P5R6S9T1X", clone))
        .isInstanceOf(SealException.class)
        .hasMessage("No draft nr-01JBX4ZQ8K3V0M7N2P5R6S9T1X");
  }
}
