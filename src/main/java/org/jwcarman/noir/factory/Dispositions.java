package org.jwcarman.noir.factory;

import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import org.jwcarman.noir.github.GitHubPort;
import org.jwcarman.noir.github.PullRequestState;
import org.jwcarman.noir.spec.SpecJson;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;

/**
 * Records the experiment's ground truth: what became of each pull request noir opened. A delivered
 * PR that has since been merged at the head noir pushed is {@code accepted}; merged at any other
 * head (or when the delivered tip cannot be determined) is {@code modified}; closed unmerged is
 * {@code rejected}. An open PR is left alone and asked about again next time. Each item gets at
 * most one {@code pr_disposition} event.
 *
 * <p>Event data omits {@code mergedAt} when the PR was not merged and {@code deliveredSha} when the
 * bundle's tip is unavailable ({@code Event} copies its data with {@code Map.copyOf}, which rejects
 * null values); readers treat a missing key as null.
 */
public final class Dispositions {
  private final Store store;
  private final GitHubPort gh;
  private final Bundles bundles;
  private final Clock clock;

  public Dispositions(Store store, GitHubPort gh, Bundles bundles, Clock clock) {
    this.store = store;
    this.gh = gh;
    this.bundles = bundles;
    this.clock = clock;
  }

  /**
   * Checks every delivered, undecided PR. A failure on one item is reported to {@code warn} (one
   * line) and does not stop the others.
   *
   * @return the ids of the items that got a disposition
   */
  public List<String> refresh(Consumer<String> warn) {
    var recorded = new ArrayList<String>();
    List<String> ids;
    try {
      ids = store.itemIds();
    } catch (RuntimeException e) {
      warn.accept("dispositions: " + oneLine(e));
      return recorded;
    }
    for (var id : ids) {
      try {
        if (refresh(id)) {
          recorded.add(id);
        }
      } catch (RuntimeException e) {
        warn.accept(id + ": disposition check failed: " + oneLine(e));
      }
    }
    return recorded;
  }

  private boolean refresh(String id) {
    var events = store.readEvents(id);
    if (events.stream().anyMatch(e -> e.type().equals("pr_disposition"))) {
      return false;
    }
    Event delivered = null;
    for (var e : events) {
      if (e.type().equals("delivered") && e.data().get("pullRequest") instanceof Number) {
        delivered = e;
      }
    }
    if (delivered == null) {
      return false;
    }
    int number = ((Number) delivered.data().get("pullRequest")).intValue();
    var item = store.readWorkItem(id);
    var pr = gh.pullRequest(item.repo(), number);
    if (pr.status() == PullRequestState.Status.OPEN) {
      return false;
    }
    var data = new LinkedHashMap<String, Object>();
    data.put("pullRequest", number);
    data.put("draft", Boolean.TRUE.equals(delivered.data().get("draft")));
    String disposition;
    if (pr.status() == PullRequestState.Status.CLOSED) {
      disposition = "rejected";
    } else {
      var tip = deliveredTip(item, delivered);
      disposition = pr.headSha() != null && pr.headSha().equals(tip) ? "accepted" : "modified";
      if (tip != null) {
        data.put("deliveredSha", tip);
      }
    }
    data.put("disposition", disposition);
    if (pr.mergedAt() != null) {
      data.put("mergedAt", pr.mergedAt().toString());
    }
    if (pr.headSha() != null) {
      data.put("headSha", pr.headSha());
    }
    store.appendEvent(Event.of(clock, id, "pr_disposition", item.attempt(), data));
    return true;
  }

  /**
   * The tip of the delivered branch in the attempt's bundle, or null if the bundle file does not
   * exist.
   */
  private String deliveredTip(org.jwcarman.noir.store.WorkItem item, Event delivered) {
    if (!(delivered.data().get("deliveredAttempt") instanceof Number attempt)) {
      return null;
    }
    var itemDir = store.itemDir(item.id());
    var bundle = new AttemptPaths(itemDir, attempt.intValue()).bundle();
    if (!Files.isRegularFile(bundle)) {
      return null;
    }
    // Only a missing bundle means "unavailable"; any read failure propagates so the item is
    // warned about and retried rather than permanently mis-recorded.
    var spec = SpecJson.read(itemDir.resolve("spec.json"));
    return bundles.tip(bundle, BranchNames.of(item.issueNumber(), spec.title()));
  }

  private static String oneLine(RuntimeException e) {
    var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return message.replaceAll("\\s+", " ").trim();
  }
}
