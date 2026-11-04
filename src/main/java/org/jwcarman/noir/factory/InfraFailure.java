package org.jwcarman.noir.factory;

/** An infrastructure operation kept failing after every retry; the work item is blocked. */
public class InfraFailure extends RuntimeException {
  public InfraFailure(Throwable cause) {
    super(cause.getMessage(), cause);
  }
}
