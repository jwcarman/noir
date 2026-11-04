package org.jwcarman.noir.store;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jwcarman.noir.NoirHome;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.spec.ItemId;

/** noir's private local store (spec §4.2). */
public final class Store {
  private static final String WORK_ITEM = "workitem.json";
  private static final String EVENTS = "events.jsonl";

  private final NoirHome home;

  public Store(NoirHome home) {
    this.home = home;
  }

  public Path itemDir(String id) {
    return home.store().resolve(id);
  }

  public Path draftDir(String id) {
    return home.drafts().resolve(id);
  }

  /** Ids of sealed work items, sorted (ids sort by creation time). */
  public List<String> itemIds() {
    if (!Files.isDirectory(home.store())) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(home.store())) {
      return s.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(ItemId::isValid)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public WorkItem readWorkItem(String id) {
    var path = itemDir(id).resolve(WORK_ITEM);
    try {
      return Json.mapper().readValue(path.toFile(), WorkItem.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path, e);
    }
  }

  public void writeWorkItem(WorkItem item) {
    try {
      var bytes = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(item);
      AtomicFiles.write(itemDir(item.id()).resolve(WORK_ITEM), bytes);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void appendEvent(Event event) {
    appendEvent(itemDir(event.itemId()), event);
  }

  /** Appends an event to the log in {@code dir} (an item directory or a draft directory). */
  public void appendEvent(Path dir, Event event) {
    var path = dir.resolve(EVENTS);
    try {
      Files.createDirectories(path.getParent());
      var line = (Json.mapper().writeValueAsString(event) + "\n").getBytes(UTF_8);
      try (var ch =
          FileChannel.open(
              path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        discardInterruptedTail(ch);
        ch.position(ch.size());
        var buf = ByteBuffer.wrap(line);
        while (buf.hasRemaining()) {
          ch.write(buf);
        }
        ch.force(true);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot append event to " + path, e);
    }
  }

  /**
   * Truncates a partial final line (left by a crash mid-append) so the next append starts on a
   * clean line.
   */
  private static void discardInterruptedTail(FileChannel ch) throws IOException {
    long size = ch.size();
    if (size == 0) {
      return;
    }
    var one = ByteBuffer.allocate(1);
    long pos = size - 1;
    ch.read(one, pos);
    if (one.get(0) == '\n') {
      return;
    }
    while (pos > 0) {
      one.clear();
      ch.read(one, pos - 1);
      if (one.get(0) == '\n') {
        break;
      }
      pos--;
    }
    ch.truncate(pos);
  }

  /**
   * Reads all events. A malformed or non-UTF-8 final line without a trailing newline (an
   * interrupted append) is skipped; any other malformed line is an error.
   */
  public List<Event> readEvents(String id) {
    return readEvents(itemDir(id));
  }

  /** Reads the events in {@code dir} (an item directory or a draft directory). */
  public List<Event> readEvents(Path dir) {
    var path = dir.resolve(EVENTS);
    if (!Files.exists(path)) {
      return List.of();
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path, e);
    }
    var events = new ArrayList<Event>();
    int start = 0;
    int lineNo = 0;
    while (start < bytes.length) {
      int end = start;
      while (end < bytes.length && bytes[end] != '\n') {
        end++;
      }
      lineNo++;
      boolean interruptedTail = end == bytes.length;
      var raw = java.util.Arrays.copyOfRange(bytes, start, end);
      start = end + 1;
      try {
        var line =
            UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
        if (line.isBlank()) {
          continue;
        }
        events.add(Json.mapper().readValue(line, Event.class));
      } catch (IOException e) {
        if (!interruptedTail) {
          throw new UncheckedIOException("Malformed event at " + path + " line " + lineNo, e);
        }
      }
    }
    return events;
  }
}
