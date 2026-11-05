package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
import org.jwcarman.noir.render.StubSource;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.Spec;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;

class SuiteSealerTest {
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
            NoirConfig.defaults(),
            clock);
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
    return suiteSealer.examine(spec, List.of(STUB), itemDir, bundle);
  }

  @Test
  void goodSuitePassesFirstAttempt() {
    var e = examine();
    assertThat(e).isEqualTo(new SuiteSealer.Examination("scripted", null));
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
            NoirConfig.defaults(),
            Clock.systemUTC());
    assertThatThrownBy(() -> s.examine(spec, List.of(STUB), itemDir, bundle))
        .isInstanceOf(ExaminerFailedException.class)
        .hasMessage("Examiner agent failed: Invalid API key · Fix external API key");
    assertThat(failing.calls).isEqualTo(1);
    assertThat(itemDir.resolve("examiner/attempt-1/agent.log"))
        .content()
        .contains("Invalid API key");
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
}
