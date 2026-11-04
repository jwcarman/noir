package org.jwcarman.noir.commit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.spec.Specs;

class CommitmentsTest {
    @TempDir Path dir;

    @Test
    void crlfAndLfHashIdentically() throws IOException {
        write("holdout/A.java", "a\r\nb\r\n");
        var crlf = Commitments.hashTree(dir, List.of("holdout"));
        write("holdout/A.java", "a\nb\n");
        assertThat(Commitments.hashTree(dir, List.of("holdout"))).isEqualTo(crlf);
        write("holdout/A.java", "a\rb\r");
        assertThat(Commitments.hashTree(dir, List.of("holdout"))).isEqualTo(crlf);
    }

    @Test
    void pathsAreRelativeSlashSeparatedAndSorted() throws IOException {
        write("holdout/b/Z.java", "z");
        write("holdout/a/Y.java", "y");
        write("reference/X.java", "x");
        assertThat(Commitments.hashTree(dir, List.of("holdout", "reference")))
                .extracting(FileHash::path)
                .containsExactly("holdout/a/Y.java", "holdout/b/Z.java", "reference/X.java");
    }

    @Test
    void missingSubdirContributesNothing() {
        assertThat(Commitments.hashTree(dir, List.of("holdout"))).isEmpty();
    }

    @Test
    void knownDigest() throws IOException {
        write("holdout/A.java", "abc");
        assertThat(Commitments.hashTree(dir, List.of("holdout")).getFirst().sha256())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsSymlink() throws IOException {
        write("holdout/Real.java", "x");
        Files.createSymbolicLink(dir.resolve("holdout/Link.java"), dir.resolve("holdout/Real.java"));
        assertThatThrownBy(() -> Commitments.hashTree(dir, List.of("holdout")))
                .isInstanceOf(SealedFileException.class)
                .hasMessageContaining("holdout/Link.java");
    }

    @Test
    void rejectsNonUtf8() throws IOException {
        Files.createDirectories(dir.resolve("holdout"));
        Files.write(dir.resolve("holdout/Bad.java"), new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThatThrownBy(() -> Commitments.hashTree(dir, List.of("holdout")))
                .isInstanceOf(SealedFileException.class)
                .hasMessageContaining("holdout/Bad.java");
    }

    @Test
    void commitmentHashIsStableAcrossWriteRead() throws IOException {
        write("stubs/src/main/java/a/Foo.java", "class Foo {}");
        write("holdout/T.java", "t");
        write("reference/R.java", "r");
        var c = Commitments.create(Specs.valid(), dir, "claude-opus-5-5");
        assertThat(c.files()).extracting(FileHash::path).containsExactly("holdout/T.java", "reference/R.java");
        Commitments.write(c, dir.resolve("commitment.json"));
        assertThat(Commitments.sha256(Commitments.read(dir.resolve("commitment.json"))))
                .isEqualTo(Commitments.sha256(c));
        assertThat(new String(Commitments.canonicalBytes(c), UTF_8))
                .startsWith("{\"baseSha\":")
                .doesNotContain("\n")
                .doesNotContain(" :");
    }

    @Test
    void specChangeChangesSpecHash() throws IOException {
        write("stubs/src/main/java/a/Foo.java", "class Foo {}");
        assertThat(Commitments.specSha256(Specs.valid(), dir))
                .isNotEqualTo(Commitments.specSha256(Specs.valid().withTitle("other"), dir));
    }

    @Test
    void stubChangeChangesSpecHash() throws IOException {
        write("stubs/src/main/java/a/Foo.java", "class Foo {}");
        var before = Commitments.specSha256(Specs.valid(), dir);
        write("stubs/src/main/java/a/Foo.java", "class Foo { int x; }");
        assertThat(Commitments.specSha256(Specs.valid(), dir)).isNotEqualTo(before);
    }

    private void write(String rel, String content) throws IOException {
        var p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
