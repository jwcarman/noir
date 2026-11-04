package org.jwcarman.noir.docker;

import java.nio.file.Path;

/** A host directory or file bind-mounted into a container. */
public record Mount(Path host, String container, boolean readOnly) {
  public static Mount ro(Path host, String container) {
    return new Mount(host, container, true);
  }

  public static Mount rw(Path host, String container) {
    return new Mount(host, container, false);
  }
}
