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
    var c = Commitments.create(Specs.valid(), dir, "claude-opus-5-5", "abc123");
    assertThat(c.files())
        .extracting(FileHash::path)
        .containsExactly("holdout/T.java", "reference/R.java");
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

  @Test
  void writeReplacesAPartialFileAtomically() throws IOException {
    write("holdout/T.java", "t");
    var c = Commitments.create(Specs.valid(), dir, "m", "deadbeef");
    var target = dir.resolve("out/commitment.json");
    Files.createDirectories(target.getParent());
    Files.writeString(target, "{\"baseSha\":\"trunc");
    // A second name for the torn file's inode: an in-place write would change what it sees.
    var oldInode = dir.resolve("old-inode.json");
    Files.createLink(oldInode, target);

    Commitments.write(c, target);

    assertThat(Commitments.read(target)).isEqualTo(c);
    assertThat(target).hasBinaryContent(Commitments.canonicalBytes(c));
    assertThat(oldInode)
        .as("target is replaced by rename, never rewritten in place")
        .hasContent("{\"baseSha\":\"trunc");
    try (var files = Files.list(target.getParent())) {
      assertThat(files).as("no temp file left behind").containsExactly(target);
    }
  }

  private void write(String rel, String content) throws IOException {
    var p = dir.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content);
  }

  @Test
  void commitmentV2IncludesBodyHash() throws IOException {
    write("holdout/T.java", "t");
    var c = Commitments.create(Specs.valid(), dir, "m", "deadbeef");
    assertThat(Commitments.VERSION).isEqualTo(2);
    assertThat(c.commitmentVersion()).isEqualTo(2);
    assertThat(c.issueBodySha256()).isEqualTo("deadbeef");
    assertThat(new String(Commitments.canonicalBytes(c), UTF_8))
        .contains("\"issueBodySha256\":\"deadbeef\"");
    assertThat(Commitments.create(Specs.valid(), dir, "m", "other")).isNotEqualTo(c);
  }

  @Test
  void v2RejectsNullBodyHash() {
    assertThatThrownBy(() -> new Commitment(2, "b", "s", null, "m", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsV1Commitment() throws IOException {
    var json =
        "{\"baseSha\":\"b\",\"commitmentVersion\":1,\"examinerModel\":\"m\",\"files\":[],"
            + "\"specSha256\":\"s\"}";
    Files.writeString(dir.resolve("c.json"), json);
    var c = Commitments.read(dir.resolve("c.json"));
    assertThat(c.commitmentVersion()).isEqualTo(1);
    assertThat(c.issueBodySha256()).isNull();
  }

  @Test
  void v1CommitmentKeepsItsOriginalHash() throws IOException {
    var v1 =
        "{\"baseSha\":\"b\",\"commitmentVersion\":1,\"examinerModel\":\"m\",\"files\":[],"
            + "\"specSha256\":\"s\"}";
    var file = dir.resolve("v1.json");
    Files.writeString(file, v1);
    assertThat(Commitments.sha256(Commitments.read(file))).isEqualTo(Commitments.sha256Hex(v1));
  }
}
