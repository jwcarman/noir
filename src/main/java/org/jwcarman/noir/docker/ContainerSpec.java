package org.jwcarman.noir.docker;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.config.NoirConfig;

/**
 * How to run one container. {@code labels} are Docker labels; every container also gets {@link
 * DockerCli#RUN_LABEL}.
 */
public record ContainerSpec(
    String image,
    List<String> command,
    List<Mount> mounts,
    Map<String, String> env,
    boolean network,
    NoirConfig.Limits limits,
    String workdir,
    Duration timeout,
    byte[] stdin,
    Map<String, String> labels) {

  /** The label naming the work item a container runs for. */
  public static final String ITEM_LABEL = "noir.item";

  public ContainerSpec {
    command = List.copyOf(command);
    mounts = List.copyOf(mounts);
    env = Map.copyOf(env);
    limits = limits == null ? NoirConfig.defaults().limits() : limits;
    timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  public static ContainerSpec of(String image, String... command) {
    return new ContainerSpec(
        image, List.of(command), List.of(), Map.of(), true, null, null, null, null, Map.of());
  }

  public ContainerSpec withCommand(List<String> v) {
    return new ContainerSpec(
        image, v, mounts, env, network, limits, workdir, timeout, stdin, labels);
  }

  public ContainerSpec withMounts(List<Mount> v) {
    return new ContainerSpec(
        image, command, v, env, network, limits, workdir, timeout, stdin, labels);
  }

  public ContainerSpec withEnv(Map<String, String> v) {
    return new ContainerSpec(
        image, command, mounts, v, network, limits, workdir, timeout, stdin, labels);
  }

  public ContainerSpec withNetwork(boolean v) {
    return new ContainerSpec(
        image, command, mounts, env, v, limits, workdir, timeout, stdin, labels);
  }

  public ContainerSpec withLimits(NoirConfig.Limits v) {
    return new ContainerSpec(
        image, command, mounts, env, network, v, workdir, timeout, stdin, labels);
  }

  public ContainerSpec withWorkdir(String v) {
    return new ContainerSpec(
        image, command, mounts, env, network, limits, v, timeout, stdin, labels);
  }

  public ContainerSpec withTimeout(Duration v) {
    return new ContainerSpec(
        image, command, mounts, env, network, limits, workdir, v, stdin, labels);
  }

  public ContainerSpec withStdin(byte[] v) {
    return new ContainerSpec(
        image, command, mounts, env, network, limits, workdir, timeout, v, labels);
  }

  public ContainerSpec withLabels(Map<String, String> v) {
    return new ContainerSpec(
        image, command, mounts, env, network, limits, workdir, timeout, stdin, v);
  }

  /** Labels this container with the work item it runs for. */
  public ContainerSpec withItem(String itemId) {
    var all = new HashMap<>(labels);
    all.put(ITEM_LABEL, itemId);
    return withLabels(all);
  }
}
