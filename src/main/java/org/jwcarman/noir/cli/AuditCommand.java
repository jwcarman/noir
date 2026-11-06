package org.jwcarman.noir.cli;

import java.nio.file.Files;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jwcarman.noir.audit.HoldoutCatalog;
import org.jwcarman.noir.audit.HoldoutCatalog.HoldoutTest;
import org.jwcarman.noir.factory.Secrets;
import org.jwcarman.noir.spec.ItemId;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Lets the human review a sealed item's hidden holdout tests per requirement and record agree or
 * disagree verdicts as calibration data. Read-only apart from appending {@code audit_verdict}
 * events: it never changes the work item, calls GitHub or runs an agent.
 */
@Command(
    name = "audit",
    description = "Review an item's holdout tests per requirement and record verdicts.")
final class AuditCommand implements Callable<Integer> {
  @ParentCommand NoirCli parent;
  @picocli.CommandLine.Spec CommandSpec spec;

  @Parameters(index = "0", description = "Work item id.")
  String itemId;

  @Option(names = "--test", description = "Holdout test id (SimpleClassName#method) to judge.")
  String test;

  @Option(names = "--agree", description = "The test is correct for its requirement.")
  boolean agree;

  @Option(names = "--disagree", description = "The test is wrong (a holdout defect).")
  boolean disagree;

  @Option(names = "--note", description = "Free-text reason for the verdict.")
  String note;

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    var err = spec.commandLine().getErr();
    var store = new Store(parent.home());
    try {
      if (!ItemId.isValid(itemId)
          || !Files.isRegularFile(store.itemDir(itemId).resolve("workitem.json"))
          || !Files.isRegularFile(store.itemDir(itemId).resolve("spec.json"))) {
        err.println("No work item " + itemId);
        return 1;
      }
      if (agree && disagree) {
        err.println("Choose either --agree or --disagree, not both");
        return 1;
      }
      if (test == null && (agree || disagree || note != null)) {
        err.println("--agree, --disagree and --note need --test");
        return 1;
      }
      if (test != null && !agree && !disagree) {
        err.println("--test needs --agree or --disagree");
        return 1;
      }
      var itemDir = store.itemDir(itemId);
      if (!java.nio.file.Files.isDirectory(itemDir.resolve("holdout"))) {
        err.println(itemId + " has no hidden tests: it was sealed with hidden tests off");
        return 1;
      }
      var itemSpec = SpecJson.read(itemDir.resolve("spec.json"));
      var tests = HoldoutCatalog.load(itemSpec, itemDir.resolve("holdout"));
      if (test == null) {
        out.print(listing(itemSpec, tests, verdicts(store)));
        return 0;
      }
      var chosen = tests.stream().filter(t -> t.id().equals(test)).findFirst().orElse(null);
      if (chosen == null) {
        err.println("No holdout test " + test + " in " + itemId);
        return 1;
      }
      var verdict = agree ? "agree" : "disagree";
      var data = new LinkedHashMap<String, Object>();
      data.put("test", test);
      data.put("verdict", verdict);
      if (note != null) {
        data.put("note", note);
      }
      data.put("requirements", chosen.requirements().stream().sorted().toList());
      var item = store.readWorkItem(itemId);
      store.appendEvent(Event.of(Clock.systemUTC(), itemId, "audit_verdict", item.attempt(), data));
      out.println("Recorded " + verdict + " for " + test);
      return 0;
    } catch (HoldoutCatalog.UnreadableHoldoutException e) {
      err.println(e.getMessage());
      return 1;
    } catch (RuntimeException e) {
      err.println(Secrets.describe(e, parent.secrets()));
      return 2;
    } finally {
      out.flush();
      err.flush();
    }
  }

  /** Latest recorded verdict per test id. */
  private Map<String, String> verdicts(Store store) {
    var latest = new HashMap<String, String>();
    for (var e : store.readEvents(itemId)) {
      if (e.type().equals("audit_verdict")
          && e.data().get("test") instanceof String t
          && e.data().get("verdict") instanceof String v) {
        latest.put(t, v);
      }
    }
    return latest;
  }

  private static String listing(
      org.jwcarman.noir.spec.Spec spec, List<HoldoutTest> tests, Map<String, String> verdicts) {
    var sb = new StringBuilder();
    for (var req : spec.requirements()) {
      sb.append(req.id()).append(": ").append(req.statement()).append('\n');
      for (var t : tests) {
        if (!t.requirements().contains(req.id())) {
          continue;
        }
        sb.append("  [").append(verdicts.getOrDefault(t.id(), "-")).append("] ").append(t.id());
        sb.append('\n');
        t.source().lines().forEach(l -> sb.append("    ").append(l).append('\n'));
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
