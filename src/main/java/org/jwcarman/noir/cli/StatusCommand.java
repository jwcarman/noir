package org.jwcarman.noir.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "status", description = "Show work items and their states.")
final class StatusCommand implements Callable<Integer> {
    @ParentCommand private NoirCli parent;
    @Spec private CommandSpec spec;

    @Option(names = "--json", description = "Emit JSON for scripting.")
    private boolean json;

    @Override
    public Integer call() {
        var store = new Store(parent.home());
        var items = store.itemIds().stream().map(store::readWorkItem).toList();
        var out = spec.commandLine().getOut();
        out.print(json ? json(items) : table(items));
        out.flush();
        return 0;
    }

    private static String json(List<WorkItem> items) {
        var rows = items.stream().map(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.id());
            row.put("repo", i.repo());
            row.put("issue", i.issueNumber());
            row.put("state", i.state());
            row.put("attempt", i.attempt());
            row.put("reworkCount", i.reworkCount());
            row.put("blockReason", i.blockReason());
            return row;
        }).toList();
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("items", rows)) + "\n";
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String table(List<WorkItem> items) {
        if (items.isEmpty()) {
            return "No work items.\n";
        }
        var rows = new java.util.ArrayList<String[]>();
        rows.add(new String[] {"ID", "REPO", "ISSUE", "STATE", "ATTEMPT"});
        for (var i : items) {
            rows.add(new String[] {
                i.id(),
                i.repo(),
                i.issueNumber() == null ? "-" : "#" + i.issueNumber(),
                i.state().name(),
                String.valueOf(i.attempt())
            });
        }
        int[] widths = new int[5];
        for (var r : rows) {
            for (int c = 0; c < r.length; c++) {
                widths[c] = Math.max(widths[c], r[c].length());
            }
        }
        var sb = new StringBuilder();
        for (var r : rows) {
            for (int c = 0; c < r.length; c++) {
                sb.append(c == r.length - 1 ? r[c] : String.format("%-" + widths[c] + "s  ", r[c]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
