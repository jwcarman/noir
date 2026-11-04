package org.jwcarman.noir.exec;

/** The executable for a command could not be found. */
public class ToolMissingException extends RuntimeException {
  private final String tool;

  public ToolMissingException(String tool, Throwable cause) {
    super(tool + " is not installed or not on PATH", cause);
    this.tool = tool;
  }

  public String tool() {
    return tool;
  }
}
