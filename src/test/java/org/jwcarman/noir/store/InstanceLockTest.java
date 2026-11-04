package org.jwcarman.noir.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {
    @TempDir Path tmp;

    @Test
    void secondAcquireIsEmptyUntilReleased() {
        var lock = tmp.resolve("noir.lock");
        try (var first = InstanceLock.tryAcquire(lock).orElseThrow()) {
            assertThat(InstanceLock.tryAcquire(lock)).isEmpty();
        }
        try (var again = InstanceLock.tryAcquire(lock).orElseThrow()) {
            assertThat(again).isNotNull();
        }
    }
}
