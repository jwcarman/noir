package org.jwcarman.noir.factory;

import java.nio.file.Path;
import java.util.List;

/** Reads an attempt's git bundle (never the builder's repository). */
public interface Bundles {
  /** Commits on {@code branch} beyond {@code baseSha}. */
  List<String> commitsSince(Path bundle, String baseSha, String branch);

  /** The full patch of {@code branch} beyond {@code baseSha}, binary content included. */
  String patchSince(Path bundle, String baseSha, String branch);

  /** The full SHA of {@code branch} in the bundle. */
  String tip(Path bundle, String branch);
}
