package org.jwcarman.noir.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.lifecycle.BlockReason;
import org.jwcarman.noir.lifecycle.State;

class StoreTest {
  static final String ID = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1W";
  @TempDir Path tmp;
  Store store;

  @BeforeEach
  void setUp() {
    store = new Store(new NoirHome(tmp));
  }

  @Test
  void emptyStoreHasNoItems() {
    assertThat(store.itemIds()).isEmpty();
  }

  @Test
  void workItemRoundTrip() {
    var wi = WorkItem.sealed(ID, "jwcarman/demo", 7);
    store.writeWorkItem(wi);
    assertThat(store.readWorkItem(wi.id())).isEqualTo(wi);
    assertThat(store.itemIds()).containsExactly(wi.id());
  }

  @Test
  void findItemMatchesRepoAndIssueOldestFirst() throws IOException {
    var older = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1A";
    var newer = "nr-01JBX4ZQ8K3V0M7N2P5R6S9T1B";
    store.writeWorkItem(WorkItem.approved(newer, "jwcarman/demo", 7));
    store.writeWorkItem(WorkItem.approved(older, "jwcarman/demo", 7));
    store.writeWorkItem(WorkItem.approved(ID, "jwcarman/other", 7));
    Files.createDirectories(store.itemDir("nr-01JBX4ZQ8K3V0M7N2P5R6S9T1C"));

    assertThat(store.findItem("jwcarman/demo", 7)).contains(older);
    assertThat(store.findItems("jwcarman/demo", 7)).containsExactly(older, newer);
    assertThat(store.findItem("jwcarman/other", 7)).contains(ID);
    assertThat(store.findItem("jwcarman/demo", 8)).isEmpty();
  }

  @Test
  void blockedWorkItemRoundTrip() {
    var wi =
        WorkItem.sealed(ID, "jwcarman/demo", 7)
            .withState(State.BLOCKED)
            .withBlock(BlockReason.INFRASTRUCTURE, State.MEASURING);
    store.writeWorkItem(wi);
    assertThat(store.readWorkItem(ID)).isEqualTo(wi);
  }

  @Test
  void draftsAreNotItems() throws IOException {
    Files.createDirectories(store.draftDir(ID));
    assertThat(store.itemIds()).isEmpty();
    assertThat(store.draftDir(ID)).isEqualTo(tmp.resolve("store/drafts").resolve(ID));
  }

  @Test
  void eventsAppendInOrder() {
    var clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
    store.appendEvent(Event.of(clock, ID, "transition", 1, Map.of("to", "BUILDING")));
    store.appendEvent(Event.of(clock, ID, "agent_run", 1, Map.of("costUsd", 1.25)));
    var events = store.readEvents(ID);
    assertThat(events).extracting(Event::type).containsExactly("transition", "agent_run");
    assertThat(events.getFirst().v()).isEqualTo(1);
    assertThat(events.getFirst().ts()).isEqualTo(Instant.parse("2026-11-03T12:00:00Z"));
    assertThat(events.get(1).data()).containsEntry("costUsd", 1.25);
  }

  @Test
  void noEventsYet() {
    assertThat(store.readEvents(ID)).isEmpty();
  }

  @Test
  void truncatedFinalEventLineIsSkipped() throws IOException {
    store.appendEvent(Event.of(Clock.systemUTC(), ID, "transition", 1, Map.of()));
    Files.writeString(
        store.itemDir(ID).resolve("events.jsonl"), "{\"v\":1,\"ts\":", StandardOpenOption.APPEND);
    assertThat(store.readEvents(ID)).hasSize(1);
  }

  @Test
  void appendAfterTruncatedTailRecovers() throws IOException {
    store.appendEvent(Event.of(Clock.systemUTC(), ID, "transition", 1, Map.of()));
    Files.writeString(
        store.itemDir(ID).resolve("events.jsonl"), "{\"v\":1,\"ts\":", StandardOpenOption.APPEND);
    store.appendEvent(Event.of(Clock.systemUTC(), ID, "after_crash", 1, Map.of()));
    assertThat(store.readEvents(ID))
        .extracting(Event::type)
        .containsExactly("transition", "after_crash");
  }

  @Test
  void truncatedMultibyteTailIsSkipped() throws IOException {
    store.appendEvent(Event.of(Clock.systemUTC(), ID, "transition", 1, Map.of("bar", "≥ 80%")));
    var path = store.itemDir(ID).resolve("events.jsonl");
    var partial =
        "{\"v\":1,\"data\":{\"bar\":\"≥".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(
        path, java.util.Arrays.copyOf(partial, partial.length - 1), StandardOpenOption.APPEND);
    assertThat(store.readEvents(ID)).hasSize(1);
  }

  @Test
  void corruptMiddleEventLineFails() throws IOException {
    Files.createDirectories(store.itemDir(ID));
    Files.writeString(store.itemDir(ID).resolve("events.jsonl"), "garbage\n{\"v\":1}\n");
    assertThatThrownBy(() -> store.readEvents(ID))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("line 1");
  }
}
