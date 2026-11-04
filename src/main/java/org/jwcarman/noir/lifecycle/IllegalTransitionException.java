package org.jwcarman.noir.lifecycle;

/** A trigger that is not valid for the work item's current state. */
public class IllegalTransitionException extends RuntimeException {
  public IllegalTransitionException(State state, Trigger trigger) {
    super("Trigger " + trigger.getClass().getSimpleName() + " is not valid in state " + state);
  }
}
