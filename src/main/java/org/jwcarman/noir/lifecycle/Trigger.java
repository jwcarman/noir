package org.jwcarman.noir.lifecycle;

/** Events that drive the work-item lifecycle. */
public sealed interface Trigger {
  record Approved() implements Trigger {}

  record SealPassed() implements Trigger {}

  record SealRejected(SealRejection reason) implements Trigger {}

  record Pickup() implements Trigger {}

  record AgentExited(boolean producedCommits) implements Trigger {}

  record MeasurementFailed(Category category) implements Trigger {}

  record MeasurementPassed() implements Trigger {}

  record GradePassed() implements Trigger {}

  record GradeFailed() implements Trigger {}

  record HoldoutDefect() implements Trigger {}

  record InfraBlocked() implements Trigger {}

  record SecretFound() implements Trigger {}

  record IssueClosed() implements Trigger {}

  record CancelRequested() implements Trigger {}

  record ResumeRequested() implements Trigger {}
}
