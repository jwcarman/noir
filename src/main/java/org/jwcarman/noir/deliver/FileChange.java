package org.jwcarman.noir.deliver;

/**
 * One file changed by an attempt. {@code oldPath} is null unless the status is {@code RENAMED};
 * binary files have {@code added = deleted = 0}.
 */
public record FileChange(Status status, String path, String oldPath, int added, int deleted) {
  public enum Status {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
  }
}
