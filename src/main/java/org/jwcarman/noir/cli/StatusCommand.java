package org.jwcarman.noir.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.metrics.ExperimentMetrics;
import org.jwcarman.noir.metrics.ExperimentReport;
import org.jwcarman.noir.metrics.ItemHistory;
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

  @Option(
      names = "--refresh",
      description = "Check GitHub for the outcome of delivered pull requests first (needs gh).")
  private boolean refresh;

  @Override
  public Integer call() {
    if (refresh) {
      var err = spec.commandLine().getErr();
      try {
        parent.dispositions().refresh(err::println);
      } catch (RuntimeException e) {
        err.println("refresh failed: " + Secrets.describe(e, parent.secrets()));
      }
      err.flush();
    }
    var store = new Store(parent.home());
    var items = store.itemIds().stream().map(id -> read(store, id)).toList();
    var out = spec.commandLine().getOut();
    var histories = items.stream().map(r -> history(store, r)).flatMap(Optional::stream).toList();
    var report = ExperimentMetrics.compute(histories);
    out.print(json ? json(items, report) : table(items) + experiment(items, report));
    out.flush();
    return 0;
  }

  /** A work item, or the error that prevented reading it. Exactly one of the two is non-null. */
  private record Row(String id, WorkItem item, String error) {}

  private static Row read(Store store, String id) {
    try {
      return new Row(id, store.readWorkItem(id), null);
    } catch (RuntimeException e) {
      return new Row(id, null, e.getMessage());
    }
  }

  private static Optional<ItemHistory> history(Store store, Row r) {
    if (r.item() == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ItemHistory(r.item(), store.readEvents(r.id())));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static String experiment(List<Row> items, ExperimentReport report) {
    return items.isEmpty() ? "" : "\n" + report.render();
  }

  private static String json(List<Row> items, ExperimentReport report) {
    var rows =
        items.stream()
            .map(r -> {
              Map<String, Object> row = new LinkedHashMap<>();
              if (r.item() == null) {
                row.put("id", r.id());
                row.put("state", "UNREADABLE");
                row.put("error", r.error());
                return row;
              }
              var i = r.item();
              row.put("id", i.id());
              row.put("repo", i.repo());
              row.put("issue", i.issueNumber());
              row.put("state", i.state());
              row.put("attempt", i.attempt());
              row.put("reworkCount", i.reworkCount());
              row.put("blockReason", i.blockReason());
              return row;
            })
            .toList();
    try {
      return Json.mapper()
              .writerWithDefaultPrettyPrinter()
              .writeValueAsString(Map.of("items", rows, "metrics", report.toMap()))
          + "\n";
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String table(List<Row> items) {
    if (items.isEmpty()) {
      return "No work items.\n";
    }
    var rows = new java.util.ArrayList<String[]>();
    rows.add(new String[] {"ID", "REPO", "ISSUE", "STATE", "ATTEMPT"});
    for (var r : items) {
      var i = r.item();
      if (i == null) {
        rows.add(new String[] {r.id(), "-", "-", "UNREADABLE", "-"});
        continue;
      }
      rows.add(
          new String[] {
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
