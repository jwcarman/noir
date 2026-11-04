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
        var path = itemDir(event.itemId()).resolve(EVENTS);
        try {
            Files.createDirectories(path.getParent());
            var line = (Json.mapper().writeValueAsString(event) + "\n").getBytes(UTF_8);
            try (var ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
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
     * Reads all events. A malformed final line without a trailing newline (an interrupted append) is skipped; any other
     * malformed line is an error.
     */
    public List<Event> readEvents(String id) {
        var path = itemDir(id).resolve(EVENTS);
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            var content = Files.readString(path, UTF_8);
            var endsCleanly = content.isEmpty() || content.endsWith("\n");
            var lines = content.split("\n", -1);
            var events = new ArrayList<Event>();
            for (int i = 0; i < lines.length; i++) {
                var line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(Json.mapper().readValue(line, Event.class));
                } catch (JsonProcessingException e) {
                    boolean interruptedTail = i == lines.length - 1 && !endsCleanly;
                    if (!interruptedTail) {
                        throw new UncheckedIOException("Malformed event at " + path + " line " + (i + 1), e);
                    }
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
