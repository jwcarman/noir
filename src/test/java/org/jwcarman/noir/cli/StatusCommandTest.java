package org.jwcarman.noir.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

class StatusCommandTest {
  static final String ID = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W";
  @TempDir Path tmp;
  Store store;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(tmp));
  }

  @Test
  void emptyStore() {
    assertThat(run("status")).isEqualTo("No work items.\n");
  }

  @Test
  void listsItems() {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var out = run("status");
    assertThat(out.lines().findFirst().orElseThrow())
        .contains("ID")
        .contains("REPO")
        .contains("ISSUE")
        .contains("STATE")
        .contains("ATTEMPT");
    assertThat(out).contains(ID).contains("jwcarman/demo").contains("#7").contains("SEALED");
  }

  @Test
  void json() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/items/0/id").asText()).isEqualTo(ID);
    assertThat(node.at("/items/0/repo").asText()).isEqualTo("jwcarman/demo");
    assertThat(node.at("/items/0/state").asText()).isEqualTo("SEALED");
    assertThat(node.at("/items/0/issue").asInt()).isEqualTo(7);
    assertThat(node.at("/items/0/attempt").asInt()).isZero();
    assertThat(node.at("/items/0/reworkCount").asInt()).isZero();
    assertThat(node.at("/items/0/blockReason").isNull()).isTrue();
  }

  @Test
  void jsonEmpty() throws Exception {
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/items").isArray()).isTrue();
    assertThat(node.at("/items").size()).isZero();
  }

  @Test
  void unreadableItemDoesNotHideOthers() throws Exception {
    store.writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    var broken = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1X";
    java.nio.file.Files.createDirectories(store.itemDir(broken));
    assertThat(run("status")).contains(ID).contains(broken).contains("UNREADABLE");
    var node = Json.mapper().readTree(run("status", "--json"));
    assertThat(node.at("/items/1/id").asText()).isEqualTo(broken);
    assertThat(node.at("/items/1/state").asText()).isEqualTo("UNREADABLE");
    assertThat(node.at("/items/1/error").asText()).contains("workitem.json");
  }

  @Test
  void homeOptionOverridesDefault() {
    var other = tmp.resolve("other");
    new Store(new NoirHome(other)).writeWorkItem(WorkItem.sealed(ID, "jwcarman/demo", 7));
    assertThat(run("--home", other.toString(), "status")).contains(ID);
  }

  private String run(String... args) {
    var out = new StringWriter();
    var err = new StringWriter();
    int code = NoirCli.execute(new NoirHome(tmp), new PrintWriter(out), new PrintWriter(err), args);
    assertThat(code).as(err.toString()).isZero();
    return out.toString();
  }
}
