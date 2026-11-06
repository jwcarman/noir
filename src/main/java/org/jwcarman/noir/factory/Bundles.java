package org.jwcarman.noir.factory;

import java.nio.file.Path;
import java.util.List;
import org.jwcarman.noir.deliver.FileChange;

/** Reads an attempt's git bundle (never the builder's repository). */
public interface Bundles {
  /** Commits on {@code branch} beyond {@code baseSha}. */
  List<String> commitsSince(Path bundle, String baseSha, String branch);

  /** The full patch of {@code branch} beyond {@code baseSha}, binary content included. */
  String patchSince(Path bundle, String baseSha, String branch);

  /**
   * One {@code git diff -U0} of {@code baseSha} against {@code branch}, limited to {@code paths};
   * empty when {@code paths} is empty.
   */
  String diffSince(Path bundle, String baseSha, String branch, List<String> paths);

  /** The files {@code branch} changes beyond {@code baseSha}, in path order, renames detected. */
  List<FileChange> fileChanges(Path bundle, String baseSha, String branch);

  /** The full SHA of {@code branch} in the bundle. */
  String tip(Path bundle, String branch);
}
