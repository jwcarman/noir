package org.jwcarman.noir.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
    @TempDir Path tmp;

    @Test
    void replacesContentAndLeavesNoTempFiles() throws IOException {
        var f = tmp.resolve("workitem.json");
        AtomicFiles.write(f, "old".getBytes(UTF_8));
        AtomicFiles.write(f, "new".getBytes(UTF_8));
        assertThat(Files.readString(f)).isEqualTo("new");
        try (var s = Files.list(tmp)) {
            assertThat(s).containsExactly(f);
        }
    }

    @Test
    void createsParentDirectories() throws IOException {
        var f = tmp.resolve("a/b/c.json");
        AtomicFiles.write(f, "x".getBytes(UTF_8));
        assertThat(Files.readString(f)).isEqualTo("x");
    }
}
