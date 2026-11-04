package org.jwcarman.noir.exec;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** {@link CommandRunner} backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {
  static final java.time.Duration DRAIN_TIMEOUT = java.time.Duration.ofSeconds(10);

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
    var stdout = drain(process.getInputStream(), command.outputLimit());
    var stderr = drain(process.getErrorStream(), command.outputLimit());
    writeStdin(process, command.stdin());
    try {
      boolean finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return result(-1, stdout, stderr, true);
      }
      return result(process.exitValue(), stdout, stderr, false);
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

  private static CommandResult result(
      int exit, CompletableFuture<Drained> out, CompletableFuture<Drained> err, boolean timedOut) {
    var o = get(out, DRAIN_TIMEOUT);
    var e = get(err, DRAIN_TIMEOUT);
    return new CommandResult(exit, o.text(), e.text(), timedOut, o.truncated() || e.truncated());
  }

  /** The kept tail of one stream, and whether anything before it was dropped. */
  record Drained(String text, boolean truncated) {}

  static CompletableFuture<Drained> drain(InputStream stream) {
    return drain(stream, Command.DEFAULT_OUTPUT_LIMIT);
  }

  /**
   * Reads {@code stream} to EOF (so the child never blocks on a full pipe), keeping only the last
   * {@code limit} bytes: memory stays bounded however much the process writes.
   */
  static CompletableFuture<Drained> drain(InputStream stream, long limit) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (stream) {
            var tail = new Tail((int) limit);
            var chunk = new byte[64 * 1024];
            for (int n; (n = stream.read(chunk)) != -1; ) {
              tail.write(chunk, 0, n);
            }
            return new Drained(new String(tail.bytes(), UTF_8), tail.truncated);
          } catch (IOException e) {
            throw new UncheckedIOException("Cannot read process output: " + e.getMessage(), e);
          }
        },
        Thread::startVirtualThread);
  }

  /**
   * The last {@code limit} bytes written. Grows on demand up to {@code limit}, then becomes a ring
   * buffer whose oldest byte is at {@code start}.
   */
  static final class Tail {
    private final int limit;
    private byte[] buf;
    private int size;
    private int start;
    boolean truncated;

    Tail(int limit) {
      this.limit = limit;
      this.buf = new byte[Math.min(limit, 8192)];
    }

    void write(byte[] b, int off, int len) {
      if (size < limit) {
        int n = Math.min(len, limit - size);
        if (size + n > buf.length) {
          buf = Arrays.copyOf(buf, (int) Math.min(limit, Math.max(2L * buf.length, size + n)));
        }
        System.arraycopy(b, off, buf, size, n);
        size += n;
        off += n;
        len -= n;
      }
      if (len == 0) {
        return;
      }
      truncated = true;
      if (len >= limit) {
        off += len - limit;
        len = limit;
      }
      int first = Math.min(len, limit - start);
      System.arraycopy(b, off, buf, start, first);
      System.arraycopy(b, off + first, buf, 0, len - first);
      start = (start + len) % limit;
    }

    byte[] bytes() {
      var out = new byte[size];
      System.arraycopy(buf, start, out, 0, size - start);
      System.arraycopy(buf, 0, out, size - start, start);
      return out;
    }
  }

  /**
   * The drained output. A drain that failed or did not finish is an error, never empty output:
   * callers (such as the secret scan) must not mistake lost output for no output.
   */
  static <T> T get(CompletableFuture<T> f, java.time.Duration timeout) {
    try {
      return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading process output", e);
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof UncheckedIOException u) {
        throw u;
      }
      throw new IllegalStateException("Cannot read process output: " + cause, cause);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("Reading process output did not finish within " + timeout, e);
    }
  }
}
