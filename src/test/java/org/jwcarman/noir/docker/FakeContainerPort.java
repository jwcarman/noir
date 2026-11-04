package org.jwcarman.noir.docker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Records container specs and answers with a handler (which may write into mounted dirs). */
public final class FakeContainerPort implements ContainerPort {
  private final Function<ContainerSpec, ContainerResult> handler;
  private final List<ContainerSpec> specs = new ArrayList<>();
  private final List<String> removedLabels = new ArrayList<>();
  private RuntimeException removeFailure;

  public FakeContainerPort(Function<ContainerSpec, ContainerResult> handler) {
    this.handler = handler;
  }

  public List<ContainerSpec> specs() {
    return specs;
  }

  @Override
  public ContainerResult run(ContainerSpec spec) {
    specs.add(spec);
    return handler.apply(spec);
  }

  @Override
  public Optional<String> imageId(String image) {
    return Optional.of("sha256:fake");
  }

  @Override
  public void build(Path contextDir, String tag) {}

  /** Labels passed to {@link #removeLabelled}, in order. */
  public List<String> removedLabels() {
    return removedLabels;
  }

  public void failRemoveLabelled(RuntimeException e) {
    removeFailure = e;
  }

  @Override
  public int removeLabelled(String label) {
    removedLabels.add(label);
    if (removeFailure != null) {
      throw removeFailure;
    }
    return 0;
  }
}
