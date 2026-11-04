package org.jwcarman.noir.docker;

/** A Docker operation failed for infrastructure reasons. */
public class DockerException extends RuntimeException {
  public DockerException(String message) {
    super(message);
  }

  public DockerException(String message, Throwable cause) {
    super(message, cause);
  }
}
