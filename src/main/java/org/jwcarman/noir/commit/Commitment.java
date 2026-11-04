package org.jwcarman.noir.commit;

import java.util.List;

/** The integrity commitment posted at sealing (spec §4.4). */
public record Commitment(
    int commitmentVersion,
    String baseSha,
    String specSha256,
    String examinerModel,
    List<FileHash> files) {
  public Commitment {
    files = files == null ? List.of() : List.copyOf(files);
  }
}
