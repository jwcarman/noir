package org.jwcarman.noir.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecProblemsTest {
  static final Spec SPEC = Specs.valid();
  static final Set<String> BASE = Set.of("src/test/java/a/ATest.java");
  static final String STUB = "src/main/java/com/example/api/Foo.java";

  static SpecProblems.Read parse(String json) {
    return SpecProblems.parse(json, SPEC, BASE);
  }

  static String one(String problemJson) {
    return "{\"problems\":[" + problemJson + "]}";
  }

  static SpecProblems valid(SpecProblems.Read read) {
    assertThat(read).isInstanceOf(SpecProblems.Valid.class);
    return ((SpecProblems.Valid) read).report();
  }

  static void invalid(SpecProblems.Read read, String reasonPart) {
    assertThat(read)
        .isInstanceOfSatisfying(
            SpecProblems.Invalid.class, i -> assertThat(i.reason()).contains(reasonPart));
  }

  @Test
  void requirementOnlyIsValid() {
    var r = valid(parse(one("{\"requirement\":\"R1\",\"explanation\":\"contradicts\"}")));
    assertThat(r.problems())
        .containsExactly(new SpecProblems.Problem("R1", java.util.List.of(), "contradicts"));
  }

  @Test
  void filesOnlyIsValid() {
    var r =
        valid(
            parse(
                one(
                    "{\"files\":[\"src/test/java/a/ATest.java\",\""
                        + STUB
                        + "\"],\"explanation\":\"x\"}")));
    assertThat(r.problems().getFirst().requirement()).isNull();
    assertThat(r.problems().getFirst().files()).containsExactly("src/test/java/a/ATest.java", STUB);
  }

  @Test
  void bothIsValid() {
    valid(
        parse(
            one(
                "{\"requirement\":\"R2\",\"files\":[\"src/test/java/a/ATest.java\"],"
                    + "\"explanation\":\"x\"}")));
  }

  @Test
  void intentAndConstraintsAreValidRequirements() {
    valid(parse(one("{\"requirement\":\"intent\",\"explanation\":\"x\"}")));
    valid(parse(one("{\"requirement\":\"constraints\",\"explanation\":\"x\"}")));
  }

  @Test
  void notJson() {
    invalid(parse("{nope"), "JSON");
  }

  @Test
  void noProblems() {
    invalid(parse("{\"problems\":[]}"), "1 to 10");
    invalid(parse("{}"), "1 to 10");
  }

  @Test
  void elevenProblems() {
    var p =
        IntStream.range(0, 11)
            .mapToObj(i -> "{\"requirement\":\"R1\",\"explanation\":\"x\"}")
            .collect(Collectors.joining(","));
    invalid(parse("{\"problems\":[" + p + "]}"), "1 to 10");
  }

  @Test
  void blankExplanation() {
    invalid(parse(one("{\"requirement\":\"R1\",\"explanation\":\"  \"}")), "explanation");
    invalid(parse(one("{\"requirement\":\"R1\"}")), "explanation");
  }

  @Test
  void unknownRequirement() {
    invalid(parse(one("{\"requirement\":\"R9\",\"explanation\":\"x\"}")), "R9");
  }

  @Test
  void fileNotAtBase() {
    invalid(
        parse(one("{\"files\":[\"src/main/java/New.java\"],\"explanation\":\"x\"}")),
        "src/main/java/New.java");
  }

  @Test
  void noReference() {
    invalid(parse(one("{\"explanation\":\"x\"}")), "reference");
    invalid(parse(one("{\"files\":[],\"explanation\":\"x\"}")), "reference");
  }

  @Test
  void elevenFiles() {
    var files =
        IntStream.range(0, 11)
            .mapToObj(i -> "\"src/test/java/a/ATest.java\"")
            .collect(Collectors.joining(","));
    invalid(parse(one("{\"files\":[" + files + "],\"explanation\":\"x\"}")), "10");
  }

  @Test
  void explanationIsStrippedAndCapped() {
    var r = valid(parse(one("{\"requirement\":\"R1\",\"explanation\":\"a\\u0007b\"}")));
    assertThat(r.problems().getFirst().explanation()).isEqualTo("a b");
    var big = "y".repeat(2500);
    var capped = valid(parse(one("{\"requirement\":\"R1\",\"explanation\":\"" + big + "\"}")));
    assertThat(capped.problems().getFirst().explanation()).hasSize(SpecProblems.MAX_EXPLANATION);
  }

  @Test
  void unknownFieldsAreIgnored() {
    valid(
        parse(
            "{\"problems\":[{\"requirement\":\"R1\",\"explanation\":\"x\",\"severity\":9}],"
                + "\"note\":1}"));
  }

  @Test
  void readAbsentFile(@TempDir Path dir) {
    assertThat(SpecProblems.read(dir.resolve("SPEC-PROBLEMS.json"), SPEC, BASE))
        .isInstanceOf(SpecProblems.Absent.class);
  }

  @Test
  void readValidFile(@TempDir Path dir) throws IOException {
    var f = dir.resolve("SPEC-PROBLEMS.json");
    Files.writeString(f, one("{\"requirement\":\"R1\",\"explanation\":\"x\"}"));
    valid(SpecProblems.read(f, SPEC, BASE));
  }

  @Test
  void readSymlink(@TempDir Path dir) throws IOException {
    var target = dir.resolve("real.json");
    Files.writeString(target, one("{\"requirement\":\"R1\",\"explanation\":\"x\"}"));
    var link = Files.createSymbolicLink(dir.resolve("SPEC-PROBLEMS.json"), target);
    invalid(SpecProblems.read(link, SPEC, BASE), "regular file");
  }

  @Test
  void readOversized(@TempDir Path dir) throws IOException {
    var f = dir.resolve("SPEC-PROBLEMS.json");
    Files.writeString(f, " ".repeat((int) SpecProblems.MAX_BYTES + 1));
    invalid(SpecProblems.read(f, SPEC, BASE), "64 KiB");
  }
}
