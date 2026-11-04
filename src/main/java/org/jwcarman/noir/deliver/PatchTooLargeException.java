package org.jwcarman.noir.deliver;

/**
 * An attempt's patch is larger than noir will read. A partial patch must never be scanned for
 * secrets, and retrying will not shrink it, so callers treat this as blocking, not as transient.
 */
public class PatchTooLargeException extends RuntimeException {
  public PatchTooLargeException(String message) {
    super(message);
  }
}
