package org.jwcarman.noir.commit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The integrity commitment posted at sealing (spec §4.4). */
public record Commitment(
    int commitmentVersion,
    String baseSha,
    String specSha256,
    @JsonInclude(JsonInclude.Include.NON_NULL) String issueBodySha256,
    String examinerModel,
    List<FileHash> files) {
  public Commitment {
    if (issueBodySha256 == null && commitmentVersion >= 2) {
      throw new IllegalArgumentException("issueBodySha256 is required for commitmentVersion >= 2");
    }
    files = files == null ? List.of() : List.copyOf(files);
  }
}
