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
        + "\nFix the issue body (`noir spec check --issue <repo>#<n> --repo <clone>` shows the same"
        + " list), then "
        + READY_AGAIN;
  }

  public static String stubExists(List<String> paths) {
    return "noir did not seal this issue: these API stub files already exist on the default"
        + " branch:\n\n"
        + bullets(paths)
        + "\nStubs may only add new files; change an existing file with a `(modify)` entry."
        + " Revise the spec, then "
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

  /** The most failing tests named in the comment. */
  static final int BASE_TESTS_LIMIT = 20;

  public static String baseTestsFail(List<String> tests) {
    var shown =
        String.join(", ", tests.stream().limit(BASE_TESTS_LIMIT).map(t -> "`" + t + "`").toList());
    var more = tests.size() - BASE_TESTS_LIMIT;
    return "noir did not seal this issue: these existing tests fail on the default branch: "
        + shown
        + (more > 0 ? ", and " + more + " more" : "")
        + ". noir requires every existing test that passes before the change to pass after it,"
        + " so the default branch must be green first. Fix them, then "
        + READY_AGAIN;
  }

  public static String conflictsWithExistingTests(List<String> classes) {
    return "noir did not seal this issue: the requested change breaks existing tests that this"
        + " spec does not allow to change: "
        + String.join(", ", classes.stream().map(c -> "`" + c + "`").toList())
        + ". If those expectations should change, list their files under `## Tests to update`"
        + " and "
        + READY_AGAIN;
  }

  public static String sealed(String sha, String baseSha, String bodySha) {
    return sealed(sha, baseSha, bodySha, true);
  }

  /** The commitment comment; it ends with {@code ; hidden tests: off} when there is no holdout. */
  public static String sealed(String sha, String baseSha, String bodySha, boolean hiddenTests) {
    return sealedText(sha, baseSha, bodySha) + (hiddenTests ? "" : "; hidden tests: off");
  }

  private static String sealedText(String sha, String baseSha, String bodySha) {
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
