package org.jwcarman.noir.exec;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** {@link CommandRunner} backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {

  @Override
  public CommandResult run(Command command) {
    var pb = new ProcessBuilder(command.argv());
    if (command.cwd() != null) {
      pb.directory(command.cwd().toFile());
    }
    pb.environment().putAll(command.env());
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      if (isNotFound(e)) {
        throw new ToolMissingException(command.argv().getFirst(), e);
      }
      throw new UncheckedIOException("Cannot start " + command.argv().getFirst(), e);
    }
    var stdout = drain(process.getInputStream());
    var stderr = drain(process.getErrorStream());
    writeStdin(process, command.stdin());
    try {
      boolean finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return new CommandResult(-1, get(stdout), get(stderr), true);
      }
      return new CommandResult(process.exitValue(), get(stdout), get(stderr), false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      throw new IllegalStateException("Interrupted while running " + command.argv().getFirst(), e);
    }
  }

  private static boolean isNotFound(IOException e) {
    var msg = e.getMessage();
    return msg != null
        && !msg.contains("(in directory")
        && (msg.contains("error=2") || msg.contains("error: 2") || msg.contains("No such file"));
  }

  private static void writeStdin(Process process, byte[] stdin) {
    try (var in = process.getOutputStream()) {
      if (stdin != null) {
        in.write(stdin);
      }
    } catch (IOException e) {
      // The process may exit without reading its stdin; that is not an error here.
    }
  }

  private static CompletableFuture<String> drain(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (stream) {
            var out = new ByteArrayOutputStream();
            stream.transferTo(out);
            return out.toString(UTF_8);
          } catch (IOException e) {
            return "";
          }
        },
        Thread::startVirtualThread);
  }

  private static String get(CompletableFuture<String> f) {
    try {
      return f.get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
      return "";
    }
  }
}
