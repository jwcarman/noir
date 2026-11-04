package org.jwcarman.noir.docker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jwcarman.noir.config.NoirConfig;

/** How to run one container. */
public record ContainerSpec(
    String image,
    List<String> command,
    List<Mount> mounts,
    Map<String, String> env,
    boolean network,
    NoirConfig.Limits limits,
    String workdir,
    Duration timeout,
    byte[] stdin) {

  public ContainerSpec {
    command = List.copyOf(command);
    mounts = List.copyOf(mounts);
    env = Map.copyOf(env);
    limits = limits == null ? NoirConfig.defaults().limits() : limits;
    timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
  }

  public static ContainerSpec of(String image, String... command) {
    return new ContainerSpec(
        image, List.of(command), List.of(), Map.of(), true, null, null, null, null);
  }

  public ContainerSpec withCommand(List<String> v) {
    return new ContainerSpec(image, v, mounts, env, network, limits, workdir, timeout, stdin);
  }

  public ContainerSpec withMounts(List<Mount> v) {
    return new ContainerSpec(image, command, v, env, network, limits, workdir, timeout, stdin);
  }

  public ContainerSpec withEnv(Map<String, String> v) {
    return new ContainerSpec(image, command, mounts, v, network, limits, workdir, timeout, stdin);
  }

  public ContainerSpec withNetwork(boolean v) {
    return new ContainerSpec(image, command, mounts, env, v, limits, workdir, timeout, stdin);
  }

  public ContainerSpec withLimits(NoirConfig.Limits v) {
    return new ContainerSpec(image, command, mounts, env, network, v, workdir, timeout, stdin);
  }

  public ContainerSpec withWorkdir(String v) {
    return new ContainerSpec(image, command, mounts, env, network, limits, v, timeout, stdin);
  }

  public ContainerSpec withTimeout(Duration v) {
    return new ContainerSpec(image, command, mounts, env, network, limits, workdir, v, stdin);
  }

  public ContainerSpec withStdin(byte[] v) {
    return new ContainerSpec(image, command, mounts, env, network, limits, workdir, timeout, v);
  }
}
