package org.jwcarman.noir;

import java.nio.file.Path;

/** Root of noir's local state. All paths derive from here so tests can use a temp dir. */
public record NoirHome(Path root) {

  public static NoirHome defaultHome() {
    return new NoirHome(Path.of(System.getProperty("user.home"), ".noir"));
  }

  public Path store() {
    return root.resolve("store");
  }

  public Path drafts() {
    return store().resolve("drafts");
  }

  public Path config() {
    return root.resolve("config.yaml");
  }

  public Path lock() {
    return root.resolve("noir.lock");
  }
}
