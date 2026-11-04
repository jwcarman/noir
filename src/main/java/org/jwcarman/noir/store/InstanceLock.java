package org.jwcarman.noir.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Ensures a single running noir daemon per home directory. */
public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Acquires the lock, or returns empty if another holder (in any process) has it. */
    public static Optional<InstanceLock> tryAcquire(Path lockFile) {
        FileChannel channel = null;
        try {
            Files.createDirectories(lockFile.toAbsolutePath().getParent());
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new InstanceLock(channel, lock));
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new UncheckedIOException("Cannot open lock file " + lockFile, e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
