package org.jwcarman.noir.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jwcarman.noir.exec.Command;
import org.jwcarman.noir.exec.CommandResult;
import org.jwcarman.noir.exec.CommandRunner;

/**
 * {@link ContainerPort} backed by the {@code docker} CLI. Containers run non-root, without new
 * privileges, with resource limits, and never with the Docker socket mounted (spec §6.2).
 */
public final class DockerCli implements ContainerPort {
  private static final String DAEMON_DOWN = "Cannot connect to the Docker daemon";

  private final CommandRunner runner;
  private final Supplier<String> names;

  public DockerCli(CommandRunner runner) {
    this(runner, () -> "noir-" + UUID.randomUUID().toString().substring(0, 12));
  }

  DockerCli(CommandRunner runner, Supplier<String> names) {
    this.runner = runner;
    this.names = names;
  }

  @Override
  public ContainerResult run(ContainerSpec spec) {
    for (var m : spec.mounts()) {
      if (m.host().toString().contains("docker.sock")) {
        throw new IllegalArgumentException("Refusing to mount the Docker socket: " + m.host());
      }
    }
    var imageId = imageId(spec.image()).orElse("unknown");
    var name = names.get();
    var argv = new ArrayList<String>();
    argv.addAll(List.of("docker", "run", "--rm", "--name", name, "--init", "-i"));
    argv.addAll(List.of("--user", "1000:1000", "--security-opt", "no-new-privileges"));
    argv.addAll(List.of("--cpus", String.format(Locale.ROOT, "%.1f", spec.limits().cpus())));
    argv.addAll(List.of("--memory", spec.limits().memory()));
    argv.addAll(List.of("--pids-limit", String.valueOf(spec.limits().pids())));
    argv.addAll(List.of("--network", spec.network() ? "bridge" : "none"));
    if (spec.workdir() != null) {
      argv.addAll(List.of("-w", spec.workdir()));
    }
    for (var m : spec.mounts()) {
      argv.add("-v");
      argv.add(realPath(m.host()) + ":" + m.container() + (m.readOnly() ? ":ro" : ""));
    }
    spec.env().keySet().stream().sorted().forEach(k -> argv.addAll(List.of("-e", k)));
    argv.add(spec.image());
    argv.addAll(spec.command());

    var command = Command.of(argv).withEnv(spec.env()).withTimeout(spec.timeout());
    if (spec.stdin() != null) {
      command = command.withStdin(spec.stdin());
    }
    var r = runner.run(command);
    if (r.timedOut()) {
      runner.run(Command.of("docker", "rm", "-f", name).withTimeout(Duration.ofMinutes(1)));
    } else if (r.exitCode() == 125 || r.stderr().contains(DAEMON_DOWN)) {
      throw failure("docker run", r);
    }
    return new ContainerResult(r.exitCode(), r.stdout(), r.stderr(), r.timedOut(), imageId);
  }

  @Override
  public Optional<String> imageId(String image) {
    var r =
        runner.run(
            Command.of("docker", "image", "inspect", "--format", "{{.Id}}", image)
                .withTimeout(Duration.ofMinutes(1)));
    if (r.stderr().contains(DAEMON_DOWN)) {
      throw failure("docker image inspect", r);
    }
    return r.ok() ? Optional.of(r.stdout().trim()) : Optional.empty();
  }

  @Override
  public void build(Path contextDir, String tag) {
    var r =
        runner.run(
            Command.of("docker", "build", "-q", "-t", tag, contextDir.toString())
                .withTimeout(Duration.ofMinutes(30)));
    if (!r.ok()) {
      throw failure("docker build", r);
    }
  }

  private static DockerException failure(String what, CommandResult r) {
    if (r.stderr().contains(DAEMON_DOWN)) {
      return new DockerException("Docker is not running; start Docker Desktop");
    }
    return new DockerException(what + " failed: " + r.tail(30));
  }

  private static String realPath(Path p) {
    try {
      return p.toRealPath().toString();
    } catch (IOException e) {
      throw new UncheckedIOException("Mount source does not exist: " + p, e);
    }
  }
}
