package org.jwcarman.noir.commit;

/**
 * A sealed file's path (relative to the item directory, {@code /}-separated) and SHA-256 of its
 * normalized content.
 */
public record FileHash(String path, String sha256) {}
