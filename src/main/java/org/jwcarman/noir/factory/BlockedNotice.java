package org.jwcarman.noir.factory;

import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;
import org.jwcarman.noir.store.Event;
import org.jwcarman.noir.store.Store;
import org.jwcarman.noir.store.WorkItem;

/**
 * The one comment posted when an item blocks while {@code SEALING}: a fixed text naming only the
 * item id, never the failure (which may quote hidden tests). Posted once per block, recorded as a
 * {@code blocked_notice} event, best effort: a failure to post never changes the item.
 */
final class BlockedNotice {
  private BlockedNotice() {}

  static String text(String itemId) {
    return "noir hit an infrastructure problem while sealing this issue. Nothing about the spec is"
        + " wrong. The operator can retry with `noir resume "
        + itemId
        + "` on the noir server.";
  }

  /**
   * Posts the notice for {@code blocked} (already persisted as {@code BLOCKED}) unless this block
   * already has one. {@code poster} posts the text, guarding it against secrets; anything it throws
   * leaves the notice unposted.
   */
  static void post(Store store, Clock clock, WorkItem blocked, Consumer<String> poster) {
    if (blocked.issueNumber() == null || noticed(store, blocked.id())) {
      return;
    }
    try {
      poster.accept(text(blocked.id()));
    } catch (RuntimeException e) {
      return; // best effort: the operator still sees the block in `noir status`
    }
    store.appendEvent(
        Event.of(
            clock, blocked.id(), "blocked_notice", blocked.attempt(), Map.of("phase", "SEALING")));
  }

  /** Whether a notice was recorded after the latest transition into {@code BLOCKED}. */
  private static boolean noticed(Store store, String itemId) {
    boolean noticed = false;
    for (var e : store.readEvents(itemId)) {
      if (e.type().equals("transition") && "BLOCKED".equals(e.data().get("to"))) {
        noticed = false;
      } else if (e.type().equals("blocked_notice")) {
        noticed = true;
      }
    }
    return noticed;
  }
}
