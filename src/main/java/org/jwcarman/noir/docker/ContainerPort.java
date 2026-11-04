package org.jwcarman.noir.docker;

import java.nio.file.Path;
import java.util.Optional;

/** Runs isolated containers. */
public interface ContainerPort {
  ContainerResult run(ContainerSpec spec);

  /** The local image id for {@code image}, or empty if it is not present. */
  Optional<String> imageId(String image);

  void build(Path contextDir, String tag);

  /**
   * Force-removes every container, running or stopped, carrying {@code label} ({@code key=value});
   * returns how many there were.
   */
  int removeLabelled(String label);
}
