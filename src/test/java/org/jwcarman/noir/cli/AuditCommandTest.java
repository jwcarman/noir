package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.audit.HoldoutCatalogTest;
import org.jwcarman.noir.lifecycle.State;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.spec.Specs;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class AuditCommandTest {
  static final String ID = Specs.ID;

  @TempDir Path tmp;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();
  Store store;

  @BeforeEach
  void setUp() throws IOException {
    store = new Store(new NoirHome(tmp));
    var dir = store.itemDir(ID);
    java.nio.file.Files.createDirectories(dir);
    SpecJson.write(Specs.valid(), dir.resolve("spec.json"));
    HoldoutCatalogTest.writeFixture(dir.resolve("holdout"));
    store.writeWorkItem(
        WorkItem.sealed(ID, "jwcarman/demo", 7).withState(State.BUILDING).withAttempt(2));
  }

  int run(String... args) {
    var ops =
        new NoirCli.Operations(
            home -> (id, d) -> null,
            home ->
                (once, listener) -> {
                  throw new AssertionError("run must not be invoked");
                },
            () -> {
              throw new AssertionError("github must not be called");
            },
            Set::of);
    return NoirCli.execute(
        new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), ops, args);
  }

  @Test
  void listGroupsTestsByRequirementInSpecOrder() {
    assertThat(run("audit", ID)).isZero();

    var text = out.toString();
    int r1 = text.indexOf("R1: get returns the value last put for a key");
    int r2 = text.indexOf("R2: put throws IllegalArgumentException when key is blank");
    assertThat(r1).isGreaterThanOrEqualTo(0).isLessThan(r2);
    assertThat(text.substring(r1, r2))
        .contains("  [-] CacheTest#getsPut")
        .contains("  [-] CacheTest#both")
        .doesNotContain("rejectsBlank");
    assertThat(text.substring(r2)).contains("  [-] BlankKeyTest#rejectsBlank");
    assertThat(text).contains("\n    void getsPut() {}");
    assertThat(text).contains("\n    /** Gets what was put. */");
  }

  @Test
  void multiTagTestAppearsUnderEachRequirement() {
    run("audit", ID);

    assertThat(out.toString().split("\\[-\\] CacheTest#both", -1)).hasSize(3);
  }

  @Test
  @SuppressWarnings("unchecked")
  void verdictIsRecordedAsAnEvent() {
    int code = run("audit", ID, "--test", "CacheTest#both", "--disagree", "--note", "too strict");

    assertThat(code).isZero();
    assertThat(out.toString()).isEqualTo("Recorded disagree for CacheTest#both\n");
    var event = store.readEvents(ID).getLast();
    assertThat(event.type()).isEqualTo("audit_verdict");
    assertThat(event.attempt()).isEqualTo(2);
    assertThat(event.data())
        .containsEntry("test", "CacheTest#both")
        .containsEntry("verdict", "disagree")
        .containsEntry("note", "too strict");
    assertThat((java.util.List<Object>) event.data().get("requirements"))
        .containsExactlyInAnyOrder("R1", "R2");
  }

  @Test
  void noteIsOmittedWhenNotGiven() {
    run("audit", ID, "--test", "CacheTest#both", "--agree");

    assertThat(store.readEvents(ID).getLast().data()).doesNotContainKey("note");
  }

  @Test
  void latestVerdictWinsInListing() {
    run("audit", ID, "--test", "CacheTest#getsPut", "--agree");
    run("audit", ID, "--test", "CacheTest#getsPut", "--disagree");
    out.getBuffer().setLength(0);

    run("audit", ID);

    assertThat(out.toString()).contains("  [disagree] CacheTest#getsPut").doesNotContain("[agree]");
  }

  @Test
  void unknownTestIsAnError() {
    assertThat(run("audit", ID, "--test", "Nope#x", "--agree")).isEqualTo(1);
    assertThat(err.toString()).isEqualTo("No holdout test Nope#x in " + ID + "\n");
    assertThat(store.readEvents(ID)).isEmpty();
  }

  @Test
  void unknownItemIsAnError() {
    assertThat(run("audit", "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A")).isEqualTo(1);
    assertThat(run("audit", "not-an-id")).isEqualTo(1);
    assertThat(err.toString())
        .isEqualTo("No work item nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A\nNo work item not-an-id\n");
  }

  @Test
  void agreeAndDisagreeTogetherIsAnError() {
    assertThat(run("audit", ID, "--test", "CacheTest#both", "--agree", "--disagree")).isEqualTo(1);
    assertThat(err.toString().lines().count()).isEqualTo(1);
    assertThat(run("audit", ID, "--test", "CacheTest#both")).isEqualTo(1);
    assertThat(store.readEvents(ID)).isEmpty();
  }

  @Test
  void auditNeverChangesWorkItem() {
    var before = store.readWorkItem(ID);

    run("audit", ID);
    run("audit", ID, "--test", "CacheTest#both", "--agree");

    assertThat(store.readWorkItem(ID)).isEqualTo(before);
  }

  @Test
  void auditWithUnparseableHoldoutFails() throws IOException {
    var dir =
        store
            .itemDir(ID)
            .resolve("holdout")
            .resolve(org.jwcarman.noir.spec.ItemId.holdoutPackage(ID).replace('.', '/'));
    java.nio.file.Files.writeString(dir.resolve("BrokenTest.java"), "class BrokenTest { void x( {");

    assertThat(run("audit", ID)).isEqualTo(1);
    assertThat(run("audit", ID, "--test", "CacheTest#both", "--agree")).isEqualTo(1);

    assertThat(out.toString()).isEmpty();
    assertThat(err.toString().lines().count()).isEqualTo(2);
    assertThat(err.toString()).contains("BrokenTest.java");
    assertThat(store.readEvents(ID)).isEmpty();
  }
}
