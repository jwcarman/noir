package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.noir.deliver.FileChange;
import org.jwcarman.noir.deliver.FileChange.Status;
import org.jwcarman.noir.spec.Spec;

class LockCheckTest {

  private static Spec spec(List<String> modifies, List<String> tests) {
    return new Spec(
        1, "id", "o/r", "abc", "t", "i", List.of(), List.of(), null, List.of(), modifies, tests);
  }

  private static FileChange change(Status s, String path) {
    return new FileChange(s, path, null, 1, 1);
  }

  private final Spec csv =
      spec(List.of("src/main/java/Csv.java"), List.of("src/test/java/CsvTest.java"));

  @Test
  void modifiedLockedFileIsAViolation() {
    assertThat(
            LockCheck.violation(csv, List.of(change(Status.MODIFIED, "src/main/java/Other.java"))))
        .hasValueSatisfying(
            t -> assertThat(t).startsWith("You modified `src/main/java/Other.java`."));
  }

  @Test
  void deletedLockedFileIsAViolation() {
    assertThat(LockCheck.violation(csv, List.of(change(Status.DELETED, "a.txt"))))
        .hasValueSatisfying(t -> assertThat(t).startsWith("You deleted `a.txt`."));
  }

  @Test
  void renamedLockedFileCountsAsDeletingTheOldPath() {
    var r = new FileChange(Status.RENAMED, "new.txt", "old.txt", 0, 0);
    assertThat(LockCheck.violation(csv, List.of(r)))
        .hasValueSatisfying(t -> assertThat(t).startsWith("You deleted `old.txt`."));
  }

  @Test
  void renamingAnAllowedPathAwayIsAViolation() {
    var r = new FileChange(Status.RENAMED, "new.txt", "src/main/java/Csv.java", 0, 0);
    assertThat(LockCheck.violation(csv, List.of(r)))
        .hasValueSatisfying(t -> assertThat(t).startsWith("You deleted `src/main/java/Csv.java`."));
  }

  @Test
  void modifyTargetDeletedIsAViolation() {
    assertThat(LockCheck.violation(csv, List.of(change(Status.DELETED, "src/main/java/Csv.java"))))
        .hasValueSatisfying(t -> assertThat(t).startsWith("You deleted `src/main/java/Csv.java`."));
  }

  @Test
  void listedTestDeletedIsAViolation() {
    assertThat(
            LockCheck.violation(csv, List.of(change(Status.DELETED, "src/test/java/CsvTest.java"))))
        .hasValueSatisfying(
            t -> assertThat(t).startsWith("You deleted `src/test/java/CsvTest.java`."));
  }

  @Test
  void modifyTargetAndListedTestMayChange() {
    assertThat(
            LockCheck.violation(
                csv,
                List.of(
                    change(Status.MODIFIED, "src/main/java/Csv.java"),
                    change(Status.MODIFIED, "src/test/java/CsvTest.java"))))
        .isEmpty();
  }

  @Test
  void newFilesAreAllowed() {
    assertThat(LockCheck.violation(csv, List.of(change(Status.ADDED, "src/main/java/New.java"))))
        .isEmpty();
  }

  @Test
  void pomIsLocked() {
    assertThat(LockCheck.violation(csv, List.of(change(Status.MODIFIED, "pom.xml"))))
        .hasValueSatisfying(t -> assertThat(t).contains("`pom.xml`"));
  }

  @Test
  void newFileOnlySpecLocksEveryExistingFile() {
    var s = spec(List.of(), List.of());
    assertThat(LockCheck.violation(s, List.of(change(Status.MODIFIED, "src/main/java/Csv.java"))))
        .isPresent();
  }

  @Test
  void feedbackTextIsExact() {
    var s = spec(List.of("A.java"), List.of());
    var text =
        LockCheck.violation(
            s,
            List.of(
                change(Status.MODIFIED, "pom.xml"),
                change(Status.DELETED, "B.java"),
                change(Status.MODIFIED, "A.java")));
    assertThat(text)
        .contains(
            "You modified `pom.xml` and deleted `B.java`. This spec allows changes only to:"
                + " `A.java`, and new files. Restore those files and make the change another"
                + " way.");
  }

  @Test
  void feedbackOmitsEmptyClauses() {
    var s = spec(List.of(), List.of());
    assertThat(LockCheck.violation(s, List.of(change(Status.MODIFIED, "pom.xml"))))
        .contains(
            "You modified `pom.xml`. This spec allows changes only to: new files. Restore those"
                + " files and make the change another way.");
  }
}
