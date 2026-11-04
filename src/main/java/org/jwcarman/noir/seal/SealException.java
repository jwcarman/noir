package org.jwcarman.noir.seal;

/** Sealing cannot proceed (as opposed to the draft being rejected). */
public class SealException extends RuntimeException {
  public SealException(String message) {
    super(message);
  }
}
