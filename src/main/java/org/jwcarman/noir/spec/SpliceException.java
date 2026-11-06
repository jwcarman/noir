package org.jwcarman.noir.spec;

/**
 * Thrown when an issue fragment cannot be spliced into the file at the base commit. The message is
 * one public-safe line: it starts with the file's path, then {@code ": "}, and ends with a fix
 * hint.
 */
public class SpliceException extends RuntimeException {
  public SpliceException(String message) {
    super(message);
  }
}
