# Changing Existing Code (Plan 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a noir spec extend existing classes and change existing behaviour. Fragments in the issue are spliced into files from the base commit. Locked files are enforced by a lock check. Seal checks also run the repo's existing tests, and metrics count only changed lines in modified files.

**Architecture:** A pure `StubSplicer` (JavaParser) turns a fragment and the base source into a complete stub. Everything downstream (the seal checks, API conformance, the Examiner) then keeps working on complete files under `stubs/`. Fragments are kept under `fragments/` so that SPEC.md shows only what changes. A host-side `LockCheck` uses the attempt bundle's file changes and runs before measurement. Changed-line metrics come from the attempt patch, which Factory already reads.

**Tech Stack:** Java 21, Maven, picocli, JavaParser (already a dependency), JUnit 5 + AssertJ, Docker integration tests (`-Pdocker`).

**Spec:** `docs/superpowers/specs/2026-11-05-noir-modify-existing-code-design.md`

## Global Constraints

- Package `org.jwcarman.noir`; Java 21; google-java-format via Spotless (`mvn -B -q spotless:apply` before every commit).
- Every public text stays fixed-phrase (Plan 5 secrecy rule). Issue comments name only repository paths and requirement ids, never holdout test names or source.
- `noir:ready` is never added or stripped by noir except when a seal is rejected (Plan 5).
- Verification for every task: `mvn -B clean verify` green. Tasks touching `src/main/resources/docker/**`, `seal/Container*`, `measure/Container*` or `PipelineIT` also need `mvn -B -Pdocker verify` green, run in the foreground with nothing left running.
- Commit trailer on every commit:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_014GnzDHDaZp6zmTz2XpjbJ3`
- Fixed phrases, verbatim:
  - **heading suffix:** ` (modify)`
  - **section:** `## Tests to update`
  - **rejection reason:** `conflicts_with_existing_tests`
  - **PR section:** `### Files` with lines `- Added: `…` (+N)`, `- Modified: `…` (+N −M)`, `- Tests updated: `…` (+N −M)`
  - **lock-check feedback:**
    `You modified <list> and deleted <list>. This spec allows changes only to: <list>, and new files. Restore those files and make the change another way.`
    Leave out a clause when its list is empty. Renames count as "deleted <old>".

## Review Focus

1. **CRLF base files:** splicing a CRLF file must keep CRLF on every line, including the inserted ones. Pinned in Task 2 by `splicePreservesCrlf`.
2. **Annotations on fragment members:**
   - a changed member keeps the base's annotations (for example `@Override`, `@Deprecated`) and ignores the fragment's;
   - an added member takes the fragment's annotations.
   Pinned in Task 2 by `changedMemberKeepsBaseAnnotations` and `addedMemberTakesFragmentAnnotations`.
3. **Generic and varargs signatures:** `<T> T get(Class<T> type)`, `void log(String... parts)` and `List<String>` versus `List<Integer>` parameters. Signature equality is the parameter types as written (after stripping whitespace), so these match their base twins exactly. Pinned in Task 2 by `genericAndVarargsSignaturesMatch`.
4. **Base files with a second top-level type** (a package-private helper class after the public one): the target must be found by name, and the other type must be left alone. Pinned in Task 2 by `secondTopLevelTypeIsUntouched`.
5. **A modify target whose attempt diff is deletions only:** it has no added lines, so it adds nothing to coverage, mutation or CRAP, and it must not raise an exception or count as 0%. Pinned in Task 7 by `deletionOnlyChangeAddsNoMetrics`.

---

### Task 1: Spec model — `(modify)` entries, `## Tests to update`, `spec.json` v2

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/spec/IssueSpec.java`, `Spec.java`, `SpecJson.java`, `SpecLint.java`, `src/main/java/org/jwcarman/noir/render/SpecRenderer.java`
- Test: `src/test/java/org/jwcarman/noir/spec/IssueSpecTest.java`, `SpecJsonTest.java`, `SpecLintTest.java`, `src/test/java/org/jwcarman/noir/render/SpecRendererTest.java`

**Interfaces:**
- Produces:
  - `IssueSpec` gains two components, `List<String> modifies` (stub paths whose heading had ` (modify)`, in stub order) and `List<String> testsToUpdate`. For a modify entry, `stubs` holds the **fragment** text.
  - `Spec` gains `List<String> modifies` and `List<String> testsToUpdate`, both defaulting to `List.of()` and both added after `apiStubs`. `IssueSpec.toSpec` writes `schemaVersion` 2 when either list is non-empty, else 1.
  - `SpecJson.read` accepts versions 1 and 2. Version 1 has empty lists.
  - `SpecRenderer.issueBody` and `specBody` render the ` (modify)` heading suffix and the optional `## Tests to update` section.

- [ ] **Step 1: Write failing tests**
  - `IssueSpecTest.parsesModifyHeading`: `### src/main/java/a/B.java (modify)` gives `modifies() == List.of("src/main/java/a/B.java")`, and the stub path has no suffix.
  - `IssueSpecTest.parsesTestsToUpdate`: a section between Requirements and Constraints with ``- `src/test/java/a/BTest.java` `` gives `testsToUpdate() == List.of("src/test/java/a/BTest.java")`.
  - `IssueSpecTest.testsToUpdateOutOfOrderIsAProblem`: the section placed after Constraints gives a problem that names its line.
  - `IssueSpecTest.testsToUpdateEntryMustBeBacktickedPath`: `- src/test/java/a/BTest.java` without backticks gives a problem with a fix hint.
  - `IssueSpecTest.roundTripWithModifyAndTests`: `parse(render(s)) == s` for a spec with one new stub, one modify stub and two tests.
  - `IssueSpecTest.newFileOnlySpecRendersAsBefore`: an existing fixture renders byte-identical to today.
  - `SpecLintTest`:
    - a modify path must be under `src/main/java/` and end in `.java`;
    - a test path must be under `src/test/java/` and end in `.java`;
    - duplicates are rejected;
    - a path listed in both `modifies` and `testsToUpdate` is rejected.
    Every message uses issue wording ("API contract", "Tests to update"), never field names. Extend the existing `noLintMessageNamesInternalFields` assertion to cover `modifies` and `testsToUpdate`.
  - `SpecJsonTest.readsV1WithEmptyLists` and `SpecJsonTest.writesAndReadsV2`.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='IssueSpecTest,SpecJsonTest,SpecLintTest,SpecRendererTest'`
  Expected: compile errors or assertion failures for the new tests only.

- [ ] **Step 3: Implement.**
  - The section list becomes Intent, API contract, Requirements, Tests to update (optional), Constraints, Quality bar.
  - `normalize` is unchanged: fence lines stay verbatim.
  - The renderer writes ` (modify)` after the path and emits the section only when the list is non-empty.

- [ ] **Step 4: Run the tests and see them pass**
  Run: `mvn -B -q spotless:apply && mvn -B clean verify`
  Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit** `feat: parse modify entries and Tests to update in issue specs`

### Task 2: `StubSplicer`

**Files:**
- Create: `src/main/java/org/jwcarman/noir/spec/StubSplicer.java`, `src/main/java/org/jwcarman/noir/spec/SpliceException.java`
- Test: `src/test/java/org/jwcarman/noir/spec/StubSplicerTest.java`

**Interfaces:**
- Produces:
  - `StubSplicer.splice(String path, String baseSource, String fragment) -> StubSplicer.Spliced`, where `record Spliced(String content, List<String> added, List<String> changed)`.
  - `added` and `changed` hold display signatures such as `parse(String, char)`.
  - On failure it throws `SpliceException` (a `RuntimeException`) whose `getMessage()` is one public-safe line starting with `path + ": "` and ending with a fix hint.

- [ ] **Step 1: Write failing tests** (every case in spec §4, plus Review Focus 1–4)
  - **Adding and changing members:**
    - `addsNewMethodAtEndOfType`: the content equals the base text, then the fragment's Javadoc and member, inserted before the type's closing brace. `added == ["parse(String, char)"]`.
    - `changedMemberKeepsBaseBodyAndTakesFragmentJavadoc`: the base body text is unchanged, the old Javadoc is replaced, and `changed == ["parse(String)"]`.
    - `changedMemberWithoutBaseJavadocGainsOne`.
  - **Imports and preservation:**
    - `mergesImportsWithoutDuplicates`.
    - `preservesCommentsOrderAndOtherMembers`: every base line outside the replaced Javadoc ranges and the insertion point appears unchanged and in order.
    - `splicePreservesCrlf`.
  - **Annotations, signatures and other types:**
    - `changedMemberKeepsBaseAnnotations`.
    - `addedMemberTakesFragmentAnnotations`.
    - `genericAndVarargsSignaturesMatch`.
    - `secondTopLevelTypeIsUntouched`.
  - **Rejections**, one test each, each asserting the message starts with the path:
    - the target type is missing;
    - the kind differs (class vs interface);
    - the modifiers differ;
    - a changed member's return type, modifiers, type parameters or `throws` differ;
    - a fragment body is not exactly `throw new UnsupportedOperationException();`;
    - the fragment has a field, an initializer or a nested type;
    - two fragment members share a signature;
    - the fragment does not parse;
    - the base does not parse;
    - the fragment's package differs from the base's.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest=StubSplicerTest`
  Expected: compile errors (the class is missing).

- [ ] **Step 3: Implement.**
  - Use JavaParser with `LexicalPreservingPrinter`, or splice by source ranges (positions from the parse) so that formatting outside the touched ranges is byte-identical. Range splicing is preferred, because `LexicalPreservingPrinter` can reformat.
  - Insert added members with the base file's line separator, indented like the type's first member, or 2 spaces if the type has none, with one blank line before each.
  - The signature is the name plus each parameter's type as written, with whitespace removed and `...` kept.

- [ ] **Step 4: Run the tests and see them pass** (same command; expected: PASS, then `mvn -B clean verify` green).

- [ ] **Step 5: Commit** `feat: splice issue fragments into existing source files`

### Task 3: Git plumbing — file content at a revision, and an attempt's file changes

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/git/GitPort.java`, `GitCli.java`, `src/main/java/org/jwcarman/noir/factory/Bundles.java`, `GitBundles.java`, every test fake implementing them (`FakeGitHub` siblings: grep `implements GitPort` / `implements Bundles` under `src/test`)
- Create: `src/main/java/org/jwcarman/noir/factory/FileChange.java`
- Test: `src/test/java/org/jwcarman/noir/git/GitCliTest.java`, `src/test/java/org/jwcarman/noir/factory/GitBundlesTest.java` (or the existing GitBundles test class)

**Interfaces:**
- Produces:
  - `Optional<String> GitPort.show(Path repoDir, String rev, String path)`. It is empty when the path does not exist at `rev` (exit 128). Any other failure throws `GitException`.
  - `record FileChange(Status status, String path, String oldPath, int added, int deleted)` with `enum Status { ADDED, MODIFIED, DELETED, RENAMED }`. `oldPath` is null unless the status is `RENAMED`. Binary files have `added = deleted = 0`.
  - `List<FileChange> Bundles.fileChanges(Path bundle, String baseSha, String branch)`, in path order.

- [ ] **Step 1: Write failing tests**
  - `GitCliTest.showReadsFileAtRevision` and `GitCliTest.showMissingPathIsEmpty`, using the `git/LocalOriginGit` helper or a temp repo.
  - `GitBundlesTest.fileChangesReportsEachStatusAndCounts`: a bundle whose branch adds, modifies, deletes and renames one file each. Assert all four `FileChange`s, including the line counts.
  - `GitBundlesTest.fileChangesHandlesSpacesAndUnicodeInPaths`: use `-z`.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='GitCliTest,GitBundlesTest'`

- [ ] **Step 3: Implement.**
  - `show` runs `git -C <dir> show <rev>:<path>`.
  - `fileChanges` runs `git diff -z -M --name-status` and `git diff -z -M --numstat` between `baseSha` and the branch in GitBundles' own clone of the bundle, then joins the two by path. It never runs git in a builder tree.

- [ ] **Step 4: Run the tests and see them pass** (same command; then `mvn -B clean verify`).

- [ ] **Step 5: Commit** `feat: read files at a revision and list an attempt's file changes`

### Task 4: Sealing modify specs — splice, store and prompt

**Files:**
- Modify:
  - `src/main/java/org/jwcarman/noir/seal/ApprovalSealer.java`, `ExaminerPrompt.java`
  - `src/main/resources/prompts/examiner-system.md`, `src/main/resources/prompts/builder-system.md`
  - `src/main/java/org/jwcarman/noir/render/SpecRenderer.java`
  - `src/main/java/org/jwcarman/noir/build/BuilderPrompt.java`
  - `src/main/java/org/jwcarman/noir/factory/Factory.java` (only the calls that render SPEC.md)
- Test: `src/test/java/org/jwcarman/noir/seal/ApprovalSealerTest.java`, `ExaminerPromptTest.java`, `src/test/java/org/jwcarman/noir/render/SpecRendererTest.java`, `src/test/java/org/jwcarman/noir/build/BuilderPromptTest.java`

**Interfaces:**
- Consumes: `StubSplicer.splice` (Task 2), `GitPort.show` (Task 3), `Spec.modifies` and `Spec.testsToUpdate` (Task 1).
- Produces:
  - **Item layout:** `stubs/<path>` always holds complete files, so a modify target holds its spliced content. `fragments/<path>` holds each modify target's fragment.
  - `SpecRenderer.loadContract(Spec, Path itemDir) -> List<StubSource>` is the issue view: fragments for modify targets and stubs otherwise. `SpecRenderer.loadStubs` keeps returning complete files.
  - **Rendering:** `SpecRenderer.specMd` and `specBody` (used by `BuilderPrompt` and `ExaminerPrompt`) take the contract view. `specMd` ends with a `## Files you may change` section listing each modify target, each test to update, and the line `- any new file (not in the reserved package noir.holdout)`. Every spec gets this section.
  - **Examiner user prompt:** gains `## Current source` when `modifies` is non-empty, with one `### <path>` and a fenced block of the complete spliced file per target.

- [ ] **Step 1: Write failing tests**
  - `ApprovalSealerTest.modifySpecSplicesAgainstBaseAndStoresBoth`: base has `Csv.java`. After sealing, `stubs/…/Csv.java` equals the splice result, `fragments/…/Csv.java` equals the fragment, and the commitment is posted.
  - `ApprovalSealerTest.modifyTargetMissingAtBaseIsInvalidSpec`: rejected with `INVALID_SPEC`. The comment names the path, and the Examiner never runs.
  - `ApprovalSealerTest.spliceFailureIsInvalidSpec`: the comment contains the `SpliceException` message.
  - `ApprovalSealerTest.testToUpdateMissingAtBaseIsInvalidSpec`.
  - `ApprovalSealerTest.newFileStubStillRejectedWhenItExists`: `STUB_EXISTS` is unchanged.
  - `SpecRendererTest.specMdListsFilesYouMayChange`, for a new-file-only spec and for a modify spec.
  - `SpecRendererTest.loadContractReturnsFragmentsForModifyTargets`.
  - `ExaminerPromptTest.userPromptCarriesCurrentSourceForModifyTargets`, and `…omitsCurrentSourceWithoutModifyTargets`.
  - `ExaminerPromptTest.systemPromptStatesModifyRules`: it contains `keep every public member` and `Current source`.
  - `BuilderPromptTest.systemPromptStatesLockRule`: it contains `Files you may change`.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='ApprovalSealerTest,ExaminerPromptTest,SpecRendererTest,BuilderPromptTest'`

- [ ] **Step 3: Implement.**
  - In `ApprovalSealer.seal`, after `baseSha` and before the existing stub-exists loop:
    - for modify targets, `git.show(repoDir, baseSha, path)`; empty means `INVALID_SPEC`, with the problem ``API contract: `<path>` (modify) does not exist on the default branch``;
    - for listed tests, `git.exists`; missing means `INVALID_SPEC`, with ``Tests to update: `<path>` does not exist on the default branch``;
    - splice each target, catching `SpliceException` as `INVALID_SPEC` with its message.
    The existing stub-exists check applies only to new-file stubs. `writeStubs` writes the spliced content for targets, and a new `writeFragments` writes the fragments. Add `fragments` to `SEAL_FILES`.
  - The `examiner-system.md` modify rules, verbatim:
    > When the spec modifies an existing file, the "Current source" section shows that file as it is today, with added members stubbed. Your reference implementation of that file is the complete file: keep every public member, and change only what the requirements say.
  - **API conformance needs no code change.** Measurement already compiles `stubs/` against the built classes and compares `javap -public`, and `stubs/` now holds complete files. Task 10's `modifySpecFixesExistingClass` exercises it.
  - The `builder-system.md` lock rule, verbatim:
    > Change only the files listed under "Files you may change" in SPEC.md, plus any new files. noir rejects an attempt that modifies, deletes or renames any other existing file.

- [ ] **Step 4: Run the tests and see them pass** (same command; then `mvn -B clean verify`).

- [ ] **Step 5: Commit** `feat: seal modify specs by splicing fragments against the base commit`

### Task 5: Seal check 4 — existing tests with the reference; `conflicts_with_existing_tests`

**Files:**
- Modify:
  - `src/main/resources/docker/builder/seal-check.sh`
  - `src/main/java/org/jwcarman/noir/seal/SealInputs.java`, `ContainerSealCheckRunner.java`, `SealJudge.java`, `SealProblems.java`, `SuiteSealer.java`, `ApprovalSealer.java`, `ApprovalComments.java`
  - `src/main/java/org/jwcarman/noir/lifecycle/SealRejection.java`
- Test: `SealJudgeTest`, `SealProblemsTest`, `SuiteSealerTest`, `ApprovalSealerTest`, `ApprovalCommentsTest`, `SealRejectionTest` (if present); `src/test/java/org/jwcarman/noir/seal/ContainerSealCheckRunnerIT.java` (Docker)

**Interfaces:**
- Consumes: `Spec.modifies` and `Spec.testsToUpdate` (Task 1).
- Produces:
  - **New env vars for `seal-check.sh`:** `EXISTING_TESTS=yes|no` (yes when `modifies` is non-empty) and `EXCLUDE_TESTS` (a comma-separated list of the tests-to-update paths).
  - **New problem form, from `SealJudge`:** `existing test fails with the reference: <ClassName>#<method>`.
  - `SuiteSealer.Examination` gains `List<String> conflictingTests`: the existing test classes (FQN, sorted, distinct) when the final attempt's problems are **all** of the new form. It is empty otherwise.
  - `SealRejection.CONFLICTS_WITH_EXISTING_TESTS` with event value `conflicts_with_existing_tests`.
  - `ApprovalComments.conflictsWithExistingTests(List<String> classes)`, verbatim:
    > noir did not seal this issue: the requested change breaks existing tests that this spec does not allow to change: `A`, `B`. If those expectations should change, list their files under `## Tests to update` and add `noir:ready` again.

- [ ] **Step 1: Write failing tests**
  - `SealJudgeTest.existingTestsThatDoNotCompileAreNotAConflict`
  - `SealJudgeTest.existingTestFailuresBecomeProblems`: a fixture `existing-tests` JUnit report dir with one failure.
  - `SealJudgeTest.existingTestsSkippedWhenNotRun`: no `existing-tests.exit` means no problem.
  - `SealProblemsTest.existingTestProblemIsPublicWithClassName`: `publicSummary` passes the class through and never the method.
  - `SuiteSealerTest.finalAttemptWithOnlyExistingTestFailuresReportsConflict`.
  - `SuiteSealerTest.mixedProblemsAreNotAConflict`.
  - `ApprovalSealerTest.conflictRejectsWithReasonAndComment`: `seal_rejected` with reason `conflicts_with_existing_tests`, the comment text, `noir:ready` removed, and the item in DRAFT.
  - `ContainerSealCheckRunnerIT.existingTestsRunAgainstReferenceExcludingListed`: a base with an existing test that the reference breaks, run once with it listed and once without.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='SealJudgeTest,SealProblemsTest,SuiteSealerTest,ApprovalSealerTest'`

- [ ] **Step 3: Implement.**
  - **`seal-check.sh`, prepare phase:** after `stub-build`, add a `test-deps` step running `mvn -B -Dmaven.repo.local="$M2" -DskipTests test` (network on), so the check phase can run offline.
  - **`seal-check.sh`, check phase:** after the reference runs, when `EXISTING_TESTS=yes`, add an `existing-tests` step. It deletes each `EXCLUDE_TESTS` path from `$REPO`, then runs `mvn -B -o -Dmaven.repo.local="$M2" test`, and finally copies `$REPO/target/surefire-reports` (if present) to `$OUT/existing-tests`. Its exit code is recorded as usual, and test failures make it non-zero.
  - **`SealJudge`:** after the reference checks pass, parse `existing-tests` with `JUnitXmlReports`.
    - Each failed or errored test becomes the new problem.
    - A non-zero exit with no reports means the existing tests could not run against the reference, which gives the problem `existing tests do not compile with the reference:` plus the log tail. That problem is **not** the conflict form.

- [ ] **Step 4: Run the tests and see them pass**
  Run: `mvn -B clean verify && mvn -B -Pdocker verify`

- [ ] **Step 5: Commit** `feat: seal checks run existing tests against the reference`

### Task 6: Lock check

**Files:**
- Create: `src/main/java/org/jwcarman/noir/factory/LockCheck.java`
- Modify: `src/main/java/org/jwcarman/noir/factory/Factory.java` (`measure`), `src/test/java/org/jwcarman/noir/factory/FactoryTest.java` (`FakeBundles` gains `fileChanges`)
- Test: `src/test/java/org/jwcarman/noir/factory/LockCheckTest.java`, `FactoryTest`, `PipelineIT.pomCannotSkipTheGrader` (expectation update)

**Interfaces:**
- Consumes: `Bundles.fileChanges` and `FileChange` (Task 3), `Spec.modifies` and `Spec.testsToUpdate` (Task 1).
- Produces: `LockCheck.violation(Spec spec, List<FileChange> changes) -> Optional<String>`, which returns the feedback text from Global Constraints, or empty.

- [ ] **Step 1: Write failing tests**
  - `LockCheckTest`:
    - `modifiedLockedFileIsAViolation`;
    - `deletedLockedFileIsAViolation`;
    - `renamedLockedFileCountsAsDeletingTheOldPath`;
    - `modifyTargetAndListedTestMayChange`;
    - `newFilesAreAllowed`;
    - `pomIsLocked`;
    - `newFileOnlySpecLocksEveryExistingFile`;
    - `feedbackTextIsExact`, asserting the full sentence for one modified and one deleted path with one allowed path;
    - `feedbackOmitsEmptyClauses`.
  - `FactoryTest.lockViolationIsContractViolationRework`: the fake bundle reports a modified locked file, the item goes to `REWORK` with category `CONTRACT_VIOLATION`, `FEEDBACK.md` for the next attempt contains the sentence, and measurement never runs.
  - **Update `PipelineIT.pomCannotSkipTheGrader`:** the pom-cheat builder now fails the lock check. Assert the item path shows `REWORK(CONTRACT_VIOLATION)`, and keep the assertion that the grader was not skipped.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='LockCheckTest,FactoryTest'`

- [ ] **Step 3: Implement.**
  - In `Factory.measure`, after the secret scan and before `measurer.run`:
    - call `retry.call(() -> bundles.fileChanges(…))`;
    - on a violation, record a `measurement` event with `passed=false` and `category=CONTRACT_VIOLATION`;
    - return `new Step(new Trigger.MeasurementFailed(Category.CONTRACT_VIOLATION), new FeedbackInput.ContractViolation(List.of(text)))`.
  - Paths are locked exactly when the status is not ADDED and the path, or `oldPath` for renames, is in neither `modifies` nor `testsToUpdate`. A rename of an allowed path is allowed only if both paths are allowed.

- [ ] **Step 4: Run the tests and see them pass**
  Run: `mvn -B clean verify && mvn -B -Pdocker verify`

- [ ] **Step 5: Commit** `feat: reject attempts that change locked files`

### Task 7: Changed-line metrics for modify targets

**Files:**
- Create: `src/main/java/org/jwcarman/noir/measure/DiffLines.java`
- Modify:
  - `src/main/java/org/jwcarman/noir/measure/JacocoReport.java`, `ClassCoverage.java`, `MethodCrap.java`, `PitByClass.java`, `MeasureJudge.java`
  - `src/main/java/org/jwcarman/noir/factory/Factory.java` (passes the changed lines)
- Test: `DiffLinesTest`, `JacocoReportTest`, `PitByClassTest`, `MeasureJudgeTest`

**Interfaces:**
- Consumes: the attempt patch (already read in `Factory.measure`), `Spec.modifies`.
- Produces:
  - `DiffLines.added(String patch, Set<String> paths) -> Map<String, Set<Integer>>`: new-side line numbers of `+` lines, per path. A requested path with no `+` lines maps to an empty set.
  - `ClassCoverage` gains `Map<Integer, Boolean> lines`, meaning line to covered (JaCoCo `ci > 0`).
  - `MethodCrap` gains `int firstLine` (JaCoCo `method@line`).
  - `PitByClass.parseLines(Path) -> Map<String, List<Mutant>>` with `record Mutant(int line, boolean detected)`, keyed by top-level class FQN.
  - `MeasureJudge.judge(MeasureArtifacts, QualityBar, List<String> changedClasses, Map<String, Set<Integer>> changedLinesByClass)`. The old 3-argument overload delegates with an empty map.

- [ ] **Step 1: Write failing tests**
  - `DiffLinesTest.addedLinesPerFile`, `ignoresOtherFiles`, `handlesMultipleHunks`, `deletionOnlyFileHasEmptySet`.
  - `JacocoReportTest.parsesLinesAndMethodFirstLine`: extend the `fixtures/measure/jacoco.xml` assertions.
  - `PitByClassTest.parsesMutantLines`.
  - `MeasureJudgeTest.modifyTargetCountsOnlyChangedLines`: the class is 10% covered overall but 100% on its changed lines, so the result is Passed, and the metrics' line coverage is computed over the changed lines.
  - `MeasureJudgeTest.crapCountsOnlyMethodsContainingChangedLines`: a method's lines run from its `firstLine` to the next method's `firstLine − 1`, or to the end of the class.
  - `MeasureJudgeTest.deletionOnlyChangeAddsNoMetrics` (Review Focus 5).
  - `MeasureJudgeTest.newFilesStillCountWhole`.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='DiffLinesTest,JacocoReportTest,PitByClassTest,MeasureJudgeTest'`

- [ ] **Step 3: Implement.**
  - Factory maps each modify path `src/main/java/a/b/C.java` to FQN `a.b.C` and passes `DiffLines.added(patch, Set.copyOf(spec.modifies()))` re-keyed by FQN.
  - In `quality`:
    - for a class in the map, count only lines in the set, for coverage and for PIT mutants;
    - count only the methods containing a changed line, for CRAP;
    - a class with an empty set contributes nothing.

- [ ] **Step 4: Run the tests and see them pass** (same command; then `mvn -B clean verify`).

- [ ] **Step 5: Commit** `feat: measure only changed lines in modified files`

### Task 8: PR body `### Files`

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/factory/PullRequestBody.java`, `Factory.java` (`body`)
- Test: `PullRequestBodyTest`, `FactoryTest`

**Interfaces:**
- Consumes: `Bundles.fileChanges` (Task 3), `Spec.testsToUpdate`.
- Produces: `PullRequestBody.render(…)` gains a `List<FileChange> files` parameter. When the list is non-empty, it writes `### Files` between the holdout results and `### Provenance`.

- [ ] **Step 1: Write failing tests**
  - `PullRequestBodyTest.rendersFilesSection`: the exact text for one added file (`+40`), one modified file (`+12 −3`) and one listed test (`Tests updated`, `+2 −2`). It uses U+2212 for minus, as the spec shows.
  - `PullRequestBodyTest.noFilesSectionWhenEmpty`.
  - `FactoryTest`: the delivered PR body contains `### Files`.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='PullRequestBodyTest,FactoryTest'`

- [ ] **Step 3: Implement.** Classify each change:
  - a path in `testsToUpdate` is "Tests updated";
  - otherwise ADDED is "Added";
  - otherwise MODIFIED or RENAMED is "Modified".
  DELETED changes are not listed, because the lock check makes them impossible for allowed paths. Paths go through the existing `line()` sanitizer.

- [ ] **Step 4: Run the tests and see them pass** (same command; then `mvn -B clean verify`).

- [ ] **Step 5: Commit** `feat: list changed files in the pull request body`

### Task 9: Desktop — `noir spec check --repo`, skill, README

**Files:**
- Modify:
  - `src/main/java/org/jwcarman/noir/cli/SpecCommand.java`
  - `src/main/resources/skills/noir-spec/SKILL.md`
  - `README.md`
- Test: `src/test/java/org/jwcarman/noir/cli/SpecCommandTest.java`, `NoirSpecSkillTest.java`

**Interfaces:**
- Consumes: `StubSplicer` (Task 2), `GitPort.show` and `GitPort.exists` (Task 3), `IssueSpec` (Task 1).
- Produces: `noir spec check (<file> --title <t> | --issue <owner/repo>#<n>) [--repo <dir>]`. With `--repo`, it resolves `origin/HEAD` in `<dir>` (failing with ``origin/HEAD is not set; run `git remote set-head origin --auto` `` if missing), then:
  - every modify target must exist and splice;
  - every listed test must exist;
  - every new-file stub must not exist.
  Problems are printed one per line, as today, and the exit code is 1 when there are any.

- [ ] **Step 1: Write failing tests**
  - `SpecCommandTest.repoOptionSplicesModifyTargets`: a clean exit for a good fragment against a temp clone.
  - `SpecCommandTest.repoOptionReportsSpliceFailure`.
  - `SpecCommandTest.repoOptionReportsMissingModifyTargetAndTest`.
  - `SpecCommandTest.repoOptionReportsExistingNewFileStub`.
  - `SpecCommandTest.repoOptionWithoutOriginHeadFails`.
  - `NoirSpecSkillTest.skillDescribesModifySpecs`: SKILL.md contains `(modify)`, `## Tests to update`, `noir spec check` with `--repo .`, and an instruction to search `src/test/java` for references to changed members and ask the user. Replace the Plan 5 assertion that stubs must be new files with "new-file stubs must not exist; modify targets must exist".
  - `NoirSpecSkillTest.skillNeverGuessesTestsToUpdate`: it contains `ask` within the Tests-to-update instructions.

- [ ] **Step 2: Run them and see them fail**
  Run: `mvn -B -q test -Dtest='SpecCommandTest,NoirSpecSkillTest'`

- [ ] **Step 3: Implement.**
  - **`SpecCommand`:** the `--repo` option and its checks, as above.
  - **SKILL.md section "Changing existing code":**
    1. read each target with `git show origin/<default>:<path>`;
    2. draft a fragment holding only the new and changed members, with UOE bodies;
    3. grep `src/test/java` for the changed members' names and ask which files belong under `## Tests to update`;
    4. run `noir spec check <tmpdir>/body.md --title "<title>" --repo .` until clean.
  - **README:** under "How it works", describe modify specs, the lock rule and `## Tests to update`. Link the Plan 6 spec under Design.

- [ ] **Step 4: Run the tests and see them pass** (same command; then `mvn -B clean verify`).

- [ ] **Step 5: Commit** `feat: check modify specs on the desktop and teach the skill to write them`

### Task 10: PipelineIT scenarios

**Files:**
- Modify: `src/test/java/org/jwcarman/noir/factory/PipelineIT.java`
- Create: fixtures under `src/test/resources/fixtures/modify/`:
  - `base/`: the existing Counter base plus a `CounterTest`;
  - `examiner-*`;
  - `builders/fix`, `builders/edits-locked-test`.

**Interfaces:**
- Consumes: everything above. Follow the existing `approve(...)` / `Approval` helper and its scripted Examiner and builder agents.

- [ ] **Step 1: Write the scenarios** (they fail until the fixtures and wiring line up)
  - `modifySpecFixesExistingClass`: the issue modifies `Counter.java` (a changed `increment` Javadoc plus an added `reset()`) and lists `CounterTest.java` under Tests to update. The builder fixes the class and updates the test. The path is `SEALING > SEALED > BUILDING > MEASURING > GRADING > PR_OPEN`, and the PR body contains `### Files` with `Modified:` and `Tests updated:`.
  - `builderEditingLockedTestIsReworked`: the same spec without the Tests-to-update entry. Attempt 1 edits `CounterTest.java`, and attempt 2 leaves it alone. The path contains `REWORK(CONTRACT_VIOLATION)` and ends in `PR_OPEN`.
  - `specConflictingWithLockedTestIsRejected`: the Examiner's reference changes behaviour that `CounterTest` asserts, and the test is not listed. The path is `SEALING > DRAFT`, the comment is `ApprovalComments.conflictsWithExistingTests(List.of("com.example.api.CounterTest"))`, and the reason is `conflicts_with_existing_tests`. Set `examiner.attempts` to 1 in this test's config to keep it fast.

- [ ] **Step 2: Run them**
  Run: `mvn -B -Pdocker verify -Dit.test=PipelineIT`
  Expected: all PipelineIT scenarios pass, the existing ones included.

- [ ] **Step 3: Commit** `test: pipeline scenarios for modify specs, locked edits and conflicts`

### Task 11: Acceptance on noir-test (requires the user to restart the server; stop and ask first)

- [ ] Rebuild (`mvn -B clean verify`) and run `bin/noir skill install`. Ask the user to restart `bin/noir --home ~/noir-server run`.
- [ ] Publish and approve, one at a time, merging each PR after review:
  1. **Extend:** `Slugifier.java (modify)` adds `slugify(String title, int maxLength)`.
  2. **Behaviour change:** `Csv.java (modify)` makes `parse` treat a lone `\r` as a line break, with `CsvTest.java` under Tests to update.
  3. **Conflict:** the same `Csv` change without Tests to update. Expect `SEALING > DRAFT`, the conflict comment naming `org.jwcarman.noirtest.CsvTest`, and `Seal rejections conflicts_with_existing_tests 1` in `noir status`.
- [ ] Record results in the ledger.
