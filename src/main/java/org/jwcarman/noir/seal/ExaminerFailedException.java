package org.jwcarman.noir.seal;

/** The Examiner agent itself failed (auth, API outage, crash), as opposed to writing bad tests. */
public class ExaminerFailedException extends RuntimeException {
  public ExaminerFailedException(String message) {
    super(message);
  }
}
