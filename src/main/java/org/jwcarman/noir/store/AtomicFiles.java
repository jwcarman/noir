package org.jwcarman.noir.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Crash-safe file replacement: write a sibling temp file, fsync, then atomically rename over the target. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path target, byte[] bytes) {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
            try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }
}
