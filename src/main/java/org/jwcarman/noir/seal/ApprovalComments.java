package org.jwcarman.noir.seal;

import java.util.List;
import org.jwcarman.noir.commit.Commitments;

/** The issue comments noir posts while sealing an approved issue. */
public final class ApprovalComments {
  private static final String READY_AGAIN = "add `noir:ready` again.";

  private ApprovalComments() {}

  public static String editedAfterApproval() {
    return "noir did not seal this issue: it was edited after `noir:ready` was added. Please"
        + " review the current text and "
        + READY_AGAIN;
  }

  public static String invalidSpec(List<String> problems) {
    return "noir could not read this issue as a spec:\n\n"
        + bullets(problems)
        + "\nFix the issue body (`noir spec check --issue <repo>#<n>` shows the same list), then "
        + READY_AGAIN;
  }

  public static String stubExists(List<String> paths) {
    return "noir did not seal this issue: these API stub files already exist on the default"
        + " branch:\n\n"
        + bullets(paths)
        + "\nStubs may only add new files. Revise the spec, then "
        + READY_AGAIN;
  }

  public static String suiteTooWeak(int attempts, List<String> summary) {
    return "noir could not produce a hidden test suite that passes its checks after "
        + attempts
        + " attempts:\n\n"
        + bullets(summary)
        + "\nThis usually means a requirement is ambiguous or untestable as written. Revise the"
        + " spec, then "
        + READY_AGAIN;
  }

  public static String sealed(String sha, String baseSha, String bodySha) {
    return "noir commitment `"
        + sha
        + "` (commitmentVersion "
        + Commitments.VERSION
        + ", base `"
        + baseSha
        + "`, issue body sha256 `"
        + bodySha
        + "`)";
  }

  private static String bullets(List<String> items) {
    var sb = new StringBuilder();
    items.forEach(i -> sb.append("- ").append(i).append('\n'));
    return sb.toString();
  }
}
