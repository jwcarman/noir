package org.jwcarman.noir.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jwcarman.noir.json.Json;

/** Reads and writes {@code spec.json}. */
public final class SpecJson {
    private SpecJson() {}

    public static Spec read(Path path) {
        try {
            return Json.mapper().readValue(path.toFile(), Spec.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read spec " + path + ": " + e.getMessage(), e);
        }
    }

    public static void write(Spec spec, Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), spec);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write spec " + path + ": " + e.getMessage(), e);
        }
    }
}
