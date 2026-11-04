package org.jwcarman.noir.commit;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import org.jwcarman.noir.json.Json;
import org.jwcarman.noir.spec.Spec;

/** Canonical hashing of sealed material (spec §4.4). */
public final class Commitments {
  public static final int VERSION = 1;

  private Commitments() {}

  /**
   * Hashes every regular file under {@code itemDir/<subdir>} for each subdir. Content is decoded as
   * strict UTF-8 and line endings normalized to LF before hashing. Symlinks and non-UTF-8 files are
   * rejected.
   */
  public static List<FileHash> hashTree(Path itemDir, List<String> subdirs) {
    var hashes = new ArrayList<FileHash>();
    for (var subdir : subdirs) {
      var root = itemDir.resolve(subdir);
      if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root)) {
        for (var p : (Iterable<Path>) paths::iterator) {
          var rel = relative(itemDir, p);
          if (Files.isSymbolicLink(p)) {
            throw new SealedFileException("Symlinks are not allowed in sealed material: " + rel);
          }
          if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            hashes.add(new FileHash(rel, sha256Hex(normalized(p, rel))));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    hashes.sort(Comparator.comparing(h -> h.path().getBytes(UTF_8), Arrays::compareUnsigned));
    return List.copyOf(hashes);
  }

  /** SHA-256 over the canonical JSON of the spec together with the hashes of its stub files. */
  public static String specSha256(Spec spec, Path itemDir) {
    var doc = new LinkedHashMap<String, Object>();
    doc.put("spec", spec);
    doc.put("stubs", hashTree(itemDir, List.of("stubs")));
    return sha256Hex(canonical(doc));
  }

  public static Commitment create(Spec spec, Path itemDir, String examinerModel) {
    return new Commitment(
        VERSION,
        spec.baseSha(),
        specSha256(spec, itemDir),
        examinerModel,
        hashTree(itemDir, List.of("holdout", "reference")));
  }

  public static byte[] canonicalBytes(Commitment commitment) {
    return canonical(commitment);
  }

  public static String sha256(Commitment commitment) {
    return sha256Hex(canonicalBytes(commitment));
  }

  public static void write(Commitment commitment, Path path) {
    try {
      Files.write(path, canonicalBytes(commitment));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Commitment read(Path path) {
    try {
      return Json.mapper().readValue(path.toFile(), Commitment.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] canonical(Object value) {
    try {
      return Json.canonical().writeValueAsBytes(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String relative(Path itemDir, Path p) {
    return itemDir.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
  }

  private static byte[] normalized(Path p, String rel) throws IOException {
    String text;
    try {
      text =
          UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(Files.readAllBytes(p)))
              .toString();
    } catch (CharacterCodingException e) {
      throw new SealedFileException("Sealed file is not valid UTF-8: " + rel, e);
    }
    return text.replace("\r\n", "\n").replace('\r', '\n').getBytes(UTF_8);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
