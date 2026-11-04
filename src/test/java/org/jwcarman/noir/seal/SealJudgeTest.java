package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SealJudgeTest {
  @TempDir Path out;

  /**
   * A fully successful run: 2 tests, all fail on stubs, all pass on 3 reference runs, 9/10 killed.
   */
  @BeforeEach
  void allGood() {
    exit("clone", 0);
    exit("assumptions", 0);
    file("pom.txt", "yes");
    file("modules.txt", "no");
    file("release.txt", "21");
    exit("stub-build", 0);
    exit("holdout-compile", 0);
    exit("stub-run", 1);
    junit("stub-run", "a()", false, "b()", false);
    exit("ref-build", 0);
    for (int n = 1; n <= 3; n++) {
      exit("ref-run-" + n, 0);
      junit("ref-run-" + n, "a()", true, "b()", true);
    }
    exit("pit", 0);
    pit(10, 9);
  }

  java.util.List<String> judge() {
    return SealJudge.judge(new SealArtifacts(out), 0.70);
  }

  @Test
  void allGoodHasNoProblems() {
    assertThat(judge()).isEmpty();
  }

  @Test
  void missingPom() {
    file("pom.txt", "no");
    assertThat(judge()).containsExactly("pom.xml not found at repository root");
  }

  @Test
  void multiModule() {
    file("modules.txt", "yes");
    assertThat(judge()).containsExactly("multi-module Maven builds are not supported");
  }

  @Test
  void wrongRelease() {
    file("release.txt", "17");
    assertThat(judge()).containsExactly("maven.compiler.release must be 21 (found 17)");
    file("release.txt", "null object or invalid expression");
    assertThat(judge()).containsExactly("maven.compiler.release must be 21 (found unset)");
  }

  @Test
  void stubsDoNotCompile() {
    exit("stub-build", 1);
    log("stub-build", "[ERROR] Foo.java: cannot find symbol");
    assertThat(judge())
        .containsExactly("API stubs do not compile:\n[ERROR] Foo.java: cannot find symbol");
  }

  @Test
  void holdoutDoesNotCompile() {
    exit("holdout-compile", 1);
    log("holdout-compile", "T.java:3: error");
    assertThat(judge())
        .containsExactly("holdout tests do not compile against the stubs:\nT.java:3: error");
  }

  @Test
  void noTestsDiscovered() throws IOException {
    deleteDir("stub-run");
    exit("stub-run", 0);
    assertThat(judge()).containsExactly("no holdout tests were discovered");
  }

  @Test
  void vacuousTests() {
    junit("stub-run", "a()", true, "b()", false);
    assertThat(judge())
        .containsExactly("holdout test passes against the stubs (vacuous): noir.holdout.x.T#a()");
  }

  @Test
  void skippedTestsAreRejected() {
    file(
        "stub-run/TEST-junit-jupiter.xml",
        "<testsuite>"
            + tc("a()", false)
            + "<testcase classname=\"noir.holdout.x.T\" name=\"b()\"><skipped/></testcase></testsuite>");
    assertThat(judge())
        .containsExactly(
            "holdout test is skipped (disabled or a failed assumption): noir.holdout.x.T#b()");
  }

  @Test
  void skippedAgainstReferenceIsRejected() {
    file(
        "ref-run-1/TEST-junit-jupiter.xml",
        "<testsuite>"
            + tc("a()", true)
            + "<testcase classname=\"noir.holdout.x.T\" name=\"b()\"><skipped/></testcase></testsuite>");
    assertThat(judge())
        .containsExactly(
            "holdout test is skipped (disabled or a failed assumption): noir.holdout.x.T#b()");
  }

  @Test
  void referenceDoesNotCompile() {
    exit("ref-build", 1);
    log("ref-build", "Counter.java: error");
    assertThat(judge())
        .containsExactly("reference implementation does not compile:\nCounter.java: error");
  }

  @Test
  void failsAgainstReference() {
    for (int n = 1; n <= 3; n++) {
      junit("ref-run-" + n, "a()", true, "b()", false);
    }
    assertThat(judge())
        .containsExactly(
            "holdout test fails against the reference implementation: noir.holdout.x.T#b()");
  }

  @Test
  void nondeterministic() {
    junit("ref-run-2", "a()", false, "b()", true);
    assertThat(judge()).containsExactly("holdout test is nondeterministic: noir.holdout.x.T#a()");
  }

  @Test
  void noMutants() {
    pit(0, 0);
    assertThat(judge()).containsExactly("mutation testing produced no mutants");
  }

  @Test
  void pitDidNotRun() throws IOException {
    Files.delete(out.resolve("pit/mutations.xml"));
    exit("pit", 1);
    log("pit", "PIT >> SEVERE : no tests");
    assertThat(judge())
        .containsExactly("mutation testing produced no mutants:\nPIT >> SEVERE : no tests");
  }

  @Test
  void notDiscriminating() {
    pit(20, 11);
    assertThat(judge())
        .containsExactly(
            "holdout tests are not discriminating enough: mutation score 0.55 < 0.70"
                + " (11 of 20 mutants killed)");
  }

  @Test
  void scriptDiedMidway() throws IOException {
    Files.delete(out.resolve("ref-build.exit"));
    log("ref-build", "Killed");
    assertThat(judge()).containsExactly("seal check did not complete step ref-build:\nKilled");
  }

  @Test
  void cloneFailed() {
    exit("clone", 128);
    log("clone", "fatal: bad object");
    assertThat(judge()).containsExactly("seal check failed at step clone:\nfatal: bad object");
  }

  @Test
  void logTailIsBounded() {
    exit("stub-build", 1);
    log(
        "stub-build",
        IntStream.rangeClosed(1, 500).mapToObj(i -> "line " + i).collect(Collectors.joining("\n")));
    assertThat(judge())
        .singleElement()
        .asString()
        .contains("line 500")
        .contains("line 441")
        .doesNotContain("line 440\n");
  }

  // --- helpers -------------------------------------------------------------------------------

  void exit(String step, int code) {
    file(step + ".exit", String.valueOf(code));
  }

  void log(String step, String text) {
    file(step + ".log", text + "\n");
  }

  void file(String name, String content) {
    try {
      var p = out.resolve(name);
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void junit(String dir, String n1, boolean pass1, String n2, boolean pass2) {
    file(
        dir + "/TEST-junit-jupiter.xml",
        "<testsuite>" + tc(n1, pass1) + tc(n2, pass2) + "</testsuite>");
  }

  static String tc(String name, boolean pass) {
    return "<testcase classname=\"noir.holdout.x.T\" name=\""
        + name
        + "\">"
        + (pass ? "" : "<failure message=\"boom\"/>")
        + "</testcase>";
  }

  void pit(int total, int detected) {
    var sb = new StringBuilder("<mutations>");
    for (int i = 0; i < total; i++) {
      sb.append("<mutation detected='").append(i < detected).append("'/>");
    }
    file("pit/mutations.xml", sb.append("</mutations>").toString());
  }

  void deleteDir(String dir) throws IOException {
    try (var s = Files.walk(out.resolve(dir))) {
      s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
