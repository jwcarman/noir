package org.jwcarman.noir.deliver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Records pushes instead of performing them. */
public final class FakeBranchPublisher implements BranchPublisher {
  public record Push(String repo, Path bundle, String branch) {}

  private final List<Push> pushes = new ArrayList<>();

  public List<Push> pushes() {
    return pushes;
  }

  @Override
  public void push(String repo, Path bundle, String branch) {
    pushes.add(new Push(repo, bundle, branch));
  }
}
