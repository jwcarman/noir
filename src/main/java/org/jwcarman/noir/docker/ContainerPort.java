package org.jwcarman.noir.docker;

import java.nio.file.Path;
import java.util.Optional;

/** Runs isolated containers. */
public interface ContainerPort {
  ContainerResult run(ContainerSpec spec);

  /** The local image id for {@code image}, or empty if it is not present. */
  Optional<String> imageId(String image);

  void build(Path contextDir, String tag);
}
