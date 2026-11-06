package org.jwcarman.noir.spec;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jwcarman.noir.json.Json;

/**
 * An agent's report that the spec cannot be satisfied as written. noir does not judge whether a
 * problem is real (a human does); it only rejects reports that cite nothing that exists. A report
 * names, for each problem, a requirement of the spec ({@code R1}…, {@code intent} or {@code
 * constraints}) and/or files that exist at the base commit or are stubs of the spec, plus an
 * explanation.
 */
public record SpecProblems(List<Problem> problems) {
  public static final long MAX_BYTES = 64 * 1024;
  public static final int MAX_PROBLEMS = 10;
  public static final int MAX_FILES = 10;
  public static final int MAX_EXPLANATION = 2000;

  public SpecProblems {
    problems = List.copyOf(problems);
  }

  /** One problem; {@code requirement} is null when the problem names only files. */
  public record Problem(String requirement, List<String> files, String explanation) {
    public Problem {
      files = List.copyOf(files);
    }
  }

  /** What reading a report found. */
  public sealed interface Read permits Valid, Invalid, Absent {}

  public record Valid(SpecProblems report) implements Read {}

  public record Invalid(String reason) implements Read {}

  public record Absent() implements Read {}

  /**
   * Reads the report at {@code file}: absent when there is no file; invalid when it is a symlink,
   * not a regular file, larger than {@value #MAX_BYTES} bytes, or fails {@link #parse}.
   */
  public static Read read(Path file, Spec spec, Set<String> baseFiles) {
    return read(file, spec, baseFiles, UnaryOperator.identity());
  }

  /**
   * {@link #read}, applying {@code mask} to the whole report text before parsing, so a secret is
   * masked before any field is cut to its cap (a cut secret would leave a fragment no mask finds).
   */
  public static Read read(Path file, Spec spec, Set<String> baseFiles, UnaryOperator<String> mask) {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return new Absent();
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return new Invalid("the report is not a regular file");
    }
    try {
      if (Files.size(file) > MAX_BYTES) {
        return new Invalid("the report is larger than 64 KiB");
      }
      return parse(mask.apply(Files.readString(file, StandardCharsets.UTF_8)), spec, baseFiles);
    } catch (IOException e) {
      return new Invalid("the report could not be read: " + e.getMessage());
    }
  }

  /** Validates a report's text against the spec and the base commit's files. */
  public static Read parse(String json, Spec spec, Set<String> baseFiles) {
    JsonNode root;
    try {
      root = Json.mapper().readTree(json);
    } catch (IOException e) {
      return new Invalid("the report is not valid JSON");
    }
    var items = root == null ? null : root.get("problems");
    if (items == null || !items.isArray() || items.isEmpty() || items.size() > MAX_PROBLEMS) {
      return new Invalid("the report must list 1 to 10 problems");
    }
    var requirements = new HashSet<String>(List.of("intent", "constraints"));
    spec.requirements().forEach(r -> requirements.add(r.id()));
    var files = new HashSet<>(baseFiles);
    files.addAll(spec.apiStubs());
    var problems = new ArrayList<Problem>();
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      var n = "problem " + (i + 1);
      var explanation = text(item.get("explanation"));
      if (explanation.isBlank()) {
        return new Invalid(n + " has no explanation");
      }
      var requirementNode = item.get("requirement");
      String requirement = null;
      if (requirementNode != null && !requirementNode.isNull()) {
        requirement = text(requirementNode).strip();
        if (!requirements.contains(requirement)) {
          return new Invalid(n + " names requirement " + requirement + ", which the spec lacks");
        }
      }
      var paths = new ArrayList<String>();
      var fileNodes = item.get("files");
      if (fileNodes != null && !fileNodes.isNull()) {
        if (!fileNodes.isArray() || fileNodes.size() > MAX_FILES) {
          return new Invalid(n + " must name at most 10 files");
        }
        for (var f : fileNodes) {
          var path = text(f).strip();
          if (!files.contains(path)) {
            return new Invalid(n + " names " + path + ", which is not a file at the base commit");
          }
          paths.add(path);
        }
      }
      if (requirement == null && paths.isEmpty()) {
        return new Invalid(n + " names no reference (a requirement or a file)");
      }
      problems.add(new Problem(requirement, paths, clean(explanation)));
    }
    return new Valid(new SpecProblems(problems));
  }

  private static String text(JsonNode node) {
    return node == null || !node.isTextual() ? "" : node.asText();
  }

  private static String clean(String s) {
    var t = s.replaceAll("\\p{Cntrl}+", " ").strip();
    return t.length() > MAX_EXPLANATION ? t.substring(0, MAX_EXPLANATION) : t;
  }
}
