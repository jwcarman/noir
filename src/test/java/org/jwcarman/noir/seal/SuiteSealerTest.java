package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.agent.AgentPort;
import org.jwcarman.noir.agent.AgentRequest;
import org.jwcarman.noir.agent.AgentResult;
import org.jwcarman.noir.agent.ScriptedAgent;
import org.jwcarman.noir.config.NoirConfig;
import org.jwcarman.noir.docker.DockerException;
import org.jwcarman.noir.exec.ProcessCommandRunner;
import org.jwcarman.noir.factory.InfraFailure;
import org.jwcarman.noir.factory.Retry;
import org.jwcarman.noir.git.GitCli;
import org.jwcarman.noir.git.GitPort;
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;

class SuiteSealerTest {
  static final GitPort GIT = new GitCli(new ProcessCommandRunner());
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
  static final StubSource STUB =
      new StubSource(
          "src/main/java/com/example/api/Foo.java",
          "package com.example.api;\n\n/** A foo. */\npublic interface Foo {}\n");

  @TempDir Path tmp;
  Store store;
  Path itemDir;
  Path bundle;
  Spec spec;
  Deque<String> holdoutSources;
  Deque<BiFunction<SealInputs, Path, SealArtifacts>> checkResults;
  List<SealInputs> checkCalls;
  ScriptedAgent agent;
  SealCheckRunner checks;
  Clock clock;
  SuiteSealer suiteSealer;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(tmp.resolve("home")));
    itemDir = store.itemDir(ID);
    bundle = itemDir.resolve("base.bundle");
    spec = Specs.valid();
    write(itemDir.resolve("stubs/" + STUB.path()), STUB.content());
    holdoutSources = new ArrayDeque<>();
    checkResults = new ArrayDeque<>();
    checkCalls = new ArrayList<>();
    agent =
        new ScriptedAgent((n, work) -> {
          var src = holdoutSources.isEmpty() ? GOOD_TEST : holdoutSources.pop();
          write(work.resolve("out/holdout/noir/holdout/T.java"), src);
          write(
              work.resolve("out/reference/src/main/java/com/example/api/Foo.java"),
              "package com.example.api;\npublic interface Foo {}\n");
        });
    checks =
        (inputs, out) -> {
          checkCalls.add(inputs);
          return checkResults.isEmpty()
              ? SealArtifactsFixture.good(out)
              : checkResults.pop().apply(inputs, out);
        };
    clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
    suiteSealer =
        new SuiteSealer(
            store,
            new Examiner(agent, NoirConfig.defaults()),
            checks,
            GIT,
            NoirConfig.defaults(),
            clock,
            retry());
  }

  /** Three tries, no waiting. */
  static Retry retry() {
    return new Retry(3, Duration.ZERO, Duration.ZERO, d -> {});
  }

  static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  SuiteSealer.Examination examine() {
    return suiteSealer.examine(
        spec,
        List.of(STUB),
        List.of(STUB),
        Map.of(),
        itemDir,
        bundle,
        SealArtifactsFixture.EMPTY_INVENTORY);
  }

  @Test
  void goodSuitePassesFirstAttempt() {
    var e = examine();
    assertThat(e).isEqualTo(new SuiteSealer.Examination("scripted", null, List.of()));
    assertThat(itemDir.resolve("holdout/noir/holdout/T.java")).exists();
    assertThat(itemDir.resolve("reference/src/main/java/com/example/api/Foo.java")).exists();
    assertThat(checkCalls.getFirst().bundle()).isEqualTo(bundle);
    assertThat(checkCalls.getFirst().stubsDir()).isEqualTo(itemDir.resolve("stubs"));
    assertThat(store.readEvents(itemDir))
        .singleElement()
        .satisfies(ev -> {
          assertThat(ev.type()).isEqualTo("examiner_attempt");
          assertThat(ev.data()).containsEntry("ok", true).containsEntry("attempt", 1);
        });
  }

  @Test
  void retriesFeedProblemsToNextAttempt() {
    checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    var e = examine();
    assertThat(e.problems()).isNull();
    assertThat(agent.requests()).hasSize(2);
    assertThat(agent.requests().get(1).prompt())
        .contains("holdout test passes against the stubs (vacuous)");
    assertThat(store.readEvents(itemDir))
        .extracting(Event::type)
        .containsExactly("examiner_attempt", "examiner_attempt");
  }

  @Test
  void threeFailuresReject() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    }
    var e = examine();
    assertThat(e.model()).isNull();
    assertThat(e.problems())
        .singleElement()
        .asString()
        .startsWith("holdout test passes against the stubs");
    assertThat(agent.requests()).hasSize(3);
  }

  @Test
  void transientCheckFailureRetriesOnlyTheChecks() {
    checkResults.add((in, out) -> {
      throw new DockerException("docker daemon went away");
    });
    var e = examine();
    assertThat(e).isEqualTo(new SuiteSealer.Examination("scripted", null, List.of()));
    assertThat(agent.requests()).hasSize(1);
    assertThat(checkCalls).hasSize(2);
    assertThat(store.readEvents(itemDir))
        .singleElement()
        .satisfies(ev -> assertThat(ev.data()).containsEntry("ok", true));
  }

  @Test
  void transientExaminerContainerFailureRetriesThatAttemptOnly() {
    var flaky =
        new AgentPort() {
          int calls;

          @Override
          public AgentResult run(AgentRequest r) {
            if (++calls == 1) {
              throw new DockerException("cannot start examiner container");
            }
            return agent.run(r);
          }
        };
    var s =
        new SuiteSealer(
            store,
            new Examiner(flaky, NoirConfig.defaults()),
            checks,
            GIT,
            NoirConfig.defaults(),
            clock,
            retry());
    var e =
        s.examine(
            spec,
            List.of(STUB),
            List.of(STUB),
            Map.of(),
            itemDir,
            bundle,
            SealArtifactsFixture.EMPTY_INVENTORY);
    assertThat(e.problems()).isNull();
    assertThat(flaky.calls).isEqualTo(2);
    assertThat(store.readEvents(itemDir))
        .singleElement()
        .satisfies(ev -> assertThat(ev.data()).containsEntry("attempt", 1));
  }

  @Test
  void checksThatKeepFailingBlockWithoutReExamining() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> {
        throw new DockerException("docker daemon went away");
      });
    }
    assertThatThrownBy(this::examine).isInstanceOf(InfraFailure.class);
    assertThat(agent.requests()).hasSize(1);
    assertThat(checkCalls).hasSize(3);
  }

  @Test
  void staticAnalysisProblemsSkipContainerChecks() {
    holdoutSources.add(UNTAGGED_TEST);
    var e = examine();
    assertThat(e.problems()).isNull();
    assertThat(agent.requests()).hasSize(2);
    assertThat(checkCalls).hasSize(1);
    assertThat(agent.requests().get(1).prompt()).contains("test has no requirement tag");
  }

  @Test
  void agentFailureIsInfrastructureNotTestProblems() {
    var failing =
        new AgentPort() {
          int calls;

          @Override
          public AgentResult run(AgentRequest r) {
            calls++;
            return new AgentResult(
                0,
                true,
                false,
                BigDecimal.ZERO,
                1,
                "default",
                "Invalid API key · Fix external API key",
                "sha256:img");
          }
        };
    var s =
        new SuiteSealer(
            store,
            new Examiner(failing, NoirConfig.defaults()),
            (in, out) -> SealArtifactsFixture.good(out),
            GIT,
            NoirConfig.defaults(),
            Clock.systemUTC(),
            retry());
    assertThatThrownBy(() ->
            s.examine(
                spec,
                List.of(STUB),
                List.of(STUB),
                Map.of(),
                itemDir,
                bundle,
                SealArtifactsFixture.EMPTY_INVENTORY))
        .isInstanceOf(ExaminerFailedException.class)
        .hasMessage("Examiner agent failed: Invalid API key · Fix external API key");
    assertThat(failing.calls).isEqualTo(1);
    assertThat(itemDir.resolve("examiner/attempt-1/agent.log"))
        .content()
        .contains("Invalid API key");
  }

  @Test
  void referenceFileOutsideTheStubPathsIsFedBackWithoutRunningChecks() {
    var strays = new ArrayDeque<>(List.of("src/main/java/com/example/api/Helper.java"));
    var a =
        new ScriptedAgent((n, work) -> {
          write(work.resolve("out/holdout/noir/holdout/T.java"), GOOD_TEST);
          write(
              work.resolve("out/reference/src/main/java/com/example/api/Foo.java"),
              "package com.example.api;\npublic interface Foo {}\n");
          var stray = strays.poll();
          if (stray != null) {
            write(work.resolve("out/reference/" + stray), "package com.example.api;\nclass H {}\n");
          }
        });
    var s =
        new SuiteSealer(
            store,
            new Examiner(a, NoirConfig.defaults()),
            checks,
            GIT,
            NoirConfig.defaults(),
            clock,
            retry());

    var e =
        s.examine(
            spec,
            List.of(STUB),
            List.of(STUB),
            Map.of(),
            itemDir,
            bundle,
            SealArtifactsFixture.EMPTY_INVENTORY);

    assertThat(e.problems()).isNull();
    assertThat(a.requests()).hasSize(2);
    assertThat(a.requests().get(1).prompt())
        .contains(
            "reference file not at an API stub path: src/main/java/com/example/api/Helper.java");
    assertThat(checkCalls).hasSize(1);
  }

  static final String FOO_BASE =
      "package com.example.api;\n\n/** A foo. */\npublic class Foo {\n"
          + "  public int a() {\n    return 1;\n  }\n}\n";
  static final String FOO_REFERENCE =
      "package com.example.api;\n\n/** A foo. */\npublic class Foo {\n"
          + "  public int a() {\n    return 1;\n  }\n\n"
          + "  public int b() {\n    return 2;\n  }\n}\n";

  static String mutant(String cls, int line, boolean detected) {
    return "<mutation detected='"
        + detected
        + "'><mutatedClass>"
        + cls
        + "</mutatedClass><lineNumber>"
        + line
        + "</lineNumber></mutation>";
  }

  /**
   * A suite sealer for a spec that modifies Foo: the reference adds b() (line 10 returns 2), and
   * PIT reports five surviving mutants on a() (line 6, unchanged) plus {@code changedLineMutant}.
   */
  SuiteSealer.Examination examineModifiedFoo(String changedLineMutant) {
    var a =
        new ScriptedAgent((n, work) -> {
          write(work.resolve("out/holdout/noir/holdout/T.java"), GOOD_TEST);
          write(work.resolve("out/reference/" + STUB.path()), FOO_REFERENCE);
        });
    SealCheckRunner pit =
        (in, out) -> {
          checkCalls.add(in);
          var artifacts = SealArtifactsFixture.good(out);
          var mutants = new StringBuilder("<mutations>");
          for (int i = 0; i < 5; i++) {
            mutants.append(mutant("com.example.api.Foo", 6, false));
          }
          mutants.append(changedLineMutant).append("</mutations>");
          write(out.resolve("pit/mutations.xml"), mutants.toString());
          return artifacts;
        };
    var s =
        new SuiteSealer(
            store,
            new Examiner(a, NoirConfig.defaults()),
            pit,
            GIT,
            NoirConfig.defaults(),
            clock,
            retry());
    var modifySpec = spec.withModifies(List.of(STUB.path()));
    return s.examine(
        modifySpec,
        List.of(STUB),
        List.of(STUB),
        Map.of(STUB.path(), FOO_BASE),
        itemDir,
        bundle,
        SealArtifactsFixture.EMPTY_INVENTORY);
  }

  @Test
  void modifyTargetIsScoredOnLinesTheReferenceChanged() {
    var e = examineModifiedFoo(mutant("com.example.api.Foo", 10, true));
    assertThat(e.problems()).isNull();
    assertThat(checkCalls).hasSize(1);
    assertThat(checkCalls.getFirst().targetClasses()).containsExactly("com.example.api.Foo");
  }

  @Test
  void survivingMutantOnAChangedLineStillFailsTheModifyTarget() {
    var e = examineModifiedFoo(mutant("com.example.api.Foo", 10, false));
    assertThat(e.problems())
        .containsExactly(
            "holdout tests are not discriminating enough: mutation score 0.00 < 0.70"
                + " (0 of 1 mutants killed)");
  }

  @Test
  void attemptEventRecordsModelAndImage() {
    examine();
    assertThat(store.readEvents(itemDir).getFirst().data())
        .containsEntry("model", "scripted")
        .containsEntry("imageId", "scripted");
  }

  @Test
  void stubPackagesReadsPackageDeclarations() {
    assertThat(SuiteSealer.stubPackages(List.of(STUB))).containsExactly("com.example.api");
  }

  /**
   * The Examiner cannot change the repository's tests, so an attempt whose only problems are
   * existing-test failures is a conflict in the spec: sealing stops there instead of retrying.
   */
  @Test
  void attemptWithOnlyExistingTestFailuresStopsAsConflict() {
    checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    for (int i = 0; i < 2; i++) {
      checkResults.add((in, out) ->
          SealArtifactsFixture.existingTestsFail(
              out, "com.example.b.BTest#x", "com.example.a.ATest#y", "com.example.b.BTest#z"));
    }
    var e = examine();
    assertThat(e.model()).isNull();
    assertThat(e.problems())
        .containsExactly(
            "existing test fails with the reference: com.example.b.BTest#x",
            "existing test fails with the reference: com.example.a.ATest#y",
            "existing test fails with the reference: com.example.b.BTest#z");
    assertThat(e.conflictingTests()).containsExactly("com.example.a.ATest", "com.example.b.BTest");
    assertThat(agent.requests()).hasSize(2);
  }

  @Test
  void mixedProblemsAreNotAConflict() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> {
        SealArtifactsFixture.existingTestsFail(out, "com.example.a.ATest#y");
        write(
            out.resolve("pit/mutations.xml"),
            "<mutations><mutation detected='false'/></mutations>");
        return new SealArtifacts(out);
      });
    }
    var e = examine();
    assertThat(e.problems())
        .hasSize(2)
        .contains("existing test fails with the reference: com.example.a.ATest#y");
    assertThat(e.conflictingTests()).isEmpty();
  }

  @Test
  void existingTestsThatCouldNotRunAreNotAConflict() {
    for (int i = 0; i < 3; i++) {
      checkResults.add((in, out) -> {
        SealArtifactsFixture.good(out);
        write(out.resolve("existing-tests.exit"), "1");
        write(out.resolve("existing-tests.log"), "[ERROR] cannot find symbol\n");
        return new SealArtifacts(out);
      });
    }
    var e = examine();
    assertThat(e.problems())
        .singleElement()
        .asString()
        .startsWith("existing tests could not run with the reference:");
    assertThat(e.conflictingTests()).isEmpty();
  }

  @Test
  void firstAttemptConflictIsNotRetried() {
    checkResults.add(
        (in, out) -> SealArtifactsFixture.existingTestsFail(out, "com.example.a.ATest#y"));
    checkResults.add((in, out) -> SealArtifactsFixture.vacuous(out));
    var e = examine();
    assertThat(e.conflictingTests()).containsExactly("com.example.a.ATest");
    assertThat(agent.requests()).hasSize(1);
  }
}
