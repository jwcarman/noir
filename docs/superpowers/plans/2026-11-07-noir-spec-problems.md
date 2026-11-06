# Spec Problems and Failure Explanations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the builder and Examiner report that a spec cannot be satisfied (stopping the item and
sending the issue back to draft), and make failed items explain why they failed.

**Architecture:** Agents write a JSON report file that noir validates against the spec and the base
commit's file list (`SpecProblems`). A valid builder report fires a new lifecycle trigger
`SpecProblemReported` (BUILDING → DRAFT); a valid Examiner report rejects the seal
(`SPEC_PROBLEM`). Re-approving an item that has built archives its attempts and resets its
counters. Failed items get a "Why it failed" section built from per-attempt event data
(`FailureExplanation`), plus the locked tests that failed.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Jackson (`org.jwcarman.noir.json.Json`), bash
(`measure.sh` unchanged).

**Spec:** `docs/superpowers/specs/2026-11-07-noir-spec-problems-design.md`

## Global Constraints

- Builder report path: `/work/SPEC-PROBLEMS.json` (attempt dir root, outside `/work/repo`). Examiner
  report path: `/work/out/spec-problems.json`.
- File read: no symlink, regular file, at most 64 KiB; otherwise "no report".
- Valid report: `problems` array of 1–10 entries; each has a non-blank `explanation` (control
  characters → spaces, cut to 2,000 chars) and at least one reference; every `requirement` is a spec
  requirement id or `intent` / `constraints`; `files` has at most 10 entries, each a base-commit
  file or a stub path. Unknown fields ignored.
- Invalid report → `spec_problems_invalid` event with a reason; the attempt proceeds as without it.
- Everything posted to GitHub is masked with `Secrets.mask` (via `guard`) and stripped of control
  characters.
- Hidden test names, sources, messages or traces are never published; build log tails are never
  published.
- "Why it failed" caps: contract-violation text 300 chars; NO_OUTPUT last message 10 lines and 1,000
  chars; at most 20 locked tests named, then "and N more".
- Comment and hint texts are fixed by the spec's sections 2 and 3; copy them verbatim.

## Review Focus

1. A report that lists a file only present on the builder's branch, not at base: must be invalid
   (fail closed), never stop the item. Test in Task 1.
2. An item sealed before this change (no `base-files.txt`): requirement-only references still valid,
   any file reference invalid. Test in Task 4.
3. A builder report plus a credential in the report text: the posted comment must be masked
   (`guard` throws `OutboundSecret` → item blocks with SECRET_FOUND, nothing posted). Test in Task 4.
4. Re-approval after a builder report: attempt 1 of the new run must start from `base.bundle`, not
   an archived attempt's bundle, and FEEDBACK from the old run must not be sent. Test in Task 3.
5. Attempts with no measurement (NO_OUTPUT) mixed with measured ones in "Why it failed": collapse
   only identical consecutive lines, keep attempt order. Test in Task 7.

---

### Task 1: `SpecProblems` report model and validator

**Files:**
- Create: `src/main/java/org/jwcarman/noir/spec/SpecProblems.java`
- Test: `src/test/java/org/jwcarman/noir/spec/SpecProblemsTest.java`

**Interfaces:**
- Produces:
  - `record SpecProblems(List<Problem> problems)`, `record Problem(String requirement, List<String> files, String explanation)` (nested; `requirement` nullable).
  - `sealed interface SpecProblems.Read permits Valid, Invalid, Absent`; `record Valid(SpecProblems report)`, `record Invalid(String reason)`, `record Absent()`.
  - `static SpecProblems.Read read(Path file, Spec spec, Set<String> baseFiles)` — file checks, then `parse`.
  - `static SpecProblems.Read parse(String json, Spec spec, Set<String> baseFiles)`.
  - `static final long MAX_BYTES = 64 * 1024`, `MAX_PROBLEMS = 10`, `MAX_FILES = 10`, `MAX_EXPLANATION = 2000`.

- [ ] **Step 1: Write the failing tests** (`SpecProblemsTest`, spec from `Specs.valid()` with
  requirements R1, R2; stub path from the spec; `baseFiles = Set.of("src/test/java/a/ATest.java")`):
  - `requirementOnlyIsValid`, `filesOnlyIsValid` (base file and stub path), `bothIsValid`,
    `intentAndConstraintsAreValidRequirements`.
  - Invalid with reason substring: `notJson` ("JSON"), `noProblems` / `elevenProblems` ("1 to 10"),
    `blankExplanation` ("explanation"), `unknownRequirement` ("R9"), `fileNotAtBase`
    ("src/main/java/New.java"), `noReference` ("reference"), `elevenFiles` ("10").
  - `explanationIsStrippedAndCapped`: `"a\u0007b"` → `"a b"`; 2,500 chars → 2,000.
  - `unknownFieldsAreIgnored`.
  - `readAbsentFile` → `Absent`; `readSymlink` → `Invalid`; `readOversized` (64 KiB + 1) → `Invalid`.

- [ ] **Step 2: Run** `mvn -q -B test -Dtest=SpecProblemsTest` — Expected: compilation failure (class missing).
- [ ] **Step 3: Implement** with `Json.mapper().readTree`; validation order as in Global Constraints; `Problem.files` defaults to empty.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat: spec-problem report model and validation`.

### Task 2: Base file list at sealing

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/seal/ApprovalSealer.java` (after `git.bundle`; `SEAL_FILES`)
- Test: `src/test/java/org/jwcarman/noir/seal/ApprovalSealerTest.java`

**Interfaces:**
- Produces: `ApprovalSealer.BASE_FILES = "base-files.txt"` (one path per line, sorted, from `git.listFiles(repoDir, baseSha, "")`); `static Set<String> ApprovalSealer.baseFiles(Path itemDir)` (empty set when missing).

- [ ] **Step 1: Test** `baseFileListIsWrittenAtSealing`: after a hidden-off seal, `baseFiles(itemDir())` contains `README.md` (or whatever `GitFixtures.originWithCommit` commits — check and use it). Add `BASE_FILES` to `assertRejected`'s leftover list.
- [ ] **Step 2: Run** `-Dtest=ApprovalSealerTest` — FAIL.
- [ ] **Step 3: Implement**; write before `pinBaseline`; add to `SEAL_FILES`.
- [ ] **Step 4: Run** — PASS. **Step 5: Commit** `feat: record the base commit's file list at sealing`.

### Task 3: Lifecycle — `SpecProblemReported` and restart on re-approval

**Files:**
- Modify: `lifecycle/Trigger.java`, `lifecycle/SideEffect.java`, `lifecycle/Lifecycle.java`, `factory/Transitions.java`, `factory/Factory.java` (`inputBundle` unchanged; it reads `attempts/` which is archived)
- Test: `LifecycleTest`, `TransitionsTest`

**Interfaces:**
- Produces: `record Trigger.SpecProblemReported() implements Trigger`; `SideEffect.ARCHIVE_ATTEMPTS`; `Transitions.archiveAttempts(Path itemDir) -> int` (returns run number `n`, 0 when there was no `attempts/`).

- [ ] **Step 1: Tests**
  - `LifecycleTest.specProblemFromBuildingGoesToDraft`: effects exactly `[REJECT_APPROVAL, APPLY_LABEL]`, no failure category; `specProblemIsIllegalOutsideBuilding` for every other non-terminal state.
  - `LifecycleTest.approvingAnItemThatBuiltRestartsIt`: DRAFT item with attempt 3, reworkCount 2 → SEALING, attempt 0, reworkCount 0, effects `[ARCHIVE_ATTEMPTS, APPLY_LABEL]`; attempt 0 → effects `[APPLY_LABEL]` only.
  - `TransitionsTest.reapprovalArchivesAttempts`: item dir with `attempts/1/x`; `applyExternal(…, Approved)` moves it to `runs/1/attempts/1/x`, a second time (after re-creating `attempts/`) to `runs/2/…`; appends a `restarted` event `{run: n}` before the `transition` event.
- [ ] **Step 2: Run** `-Dtest='LifecycleTest,TransitionsTest'` — FAIL.
- [ ] **Step 3: Implement**; `Transitions.HANDLED` gains `ARCHIVE_ATTEMPTS`; `applyExternal` archives (via `store.itemDir(item.id())`) before labelling. `Factory.perform` needs no case: the trigger only arrives through `applyExternal`. Make `Factory.perform`'s switch exhaustive with `case ARCHIVE_ATTEMPTS -> throw new IllegalStateException(...)`.
- [ ] **Step 4: Run** — PASS. **Step 5: Commit** `feat: spec-problem trigger; re-approval restarts a built item`.

### Task 4: Builder reports a spec problem

**Files:**
- Modify: `build/BuildOutcome.java` (add `SpecProblems.Read specProblems`), `build/ContainerBuildRunner.java`, `factory/Factory.java` (`build`), `src/main/resources/prompts/builder-system.md`
- Create: `src/main/java/org/jwcarman/noir/factory/SpecProblemComment.java`
- Test: `FactoryTest`, `ContainerBuildRunnerTest`, `SpecProblemCommentTest`

**Interfaces:**
- Consumes: Task 1 `SpecProblems.read`, Task 2 `ApprovalSealer.baseFiles`, Task 3 trigger.
- Produces: `SpecProblemComment.render(String agent, SpecProblems report) -> String` (agent is `"builder"` or `"Examiner"`); text exactly per spec §2 "Issue comment"; each bullet `- **R1** (`a`, `b`): explanation` — omit the bold part when no requirement, omit the parenthesis when no files.
- `BuildOutcome` keeps its 3-arg constructor delegating with `new SpecProblems.Absent()`, so other callers compile.

- [ ] **Step 1: Tests**
  - `SpecProblemCommentTest.textIsExact` (both agents, all three bullet shapes).
  - `ContainerBuildRunnerTest.reportIsReadFromTheAttemptDir`: the fake agent writes `SPEC-PROBLEMS.json` in the attempt dir → outcome `Valid`.
  - `FactoryTest.builderReportStopsTheItem` (with and without commits): state DRAFT, `noir:ready` removed, comment equals `SpecProblemComment.render("builder", …)`, events contain `spec_problems` then a `transition` to DRAFT; `build.calls` is 1; no draft PR.
  - `FactoryTest.invalidBuilderReportIsIgnored`: reference to `R9` → `spec_problems_invalid` event, item proceeds to PR_OPEN.
  - `FactoryTest.reportWithASecretBlocks`: explanation containing `SECRET` → BLOCKED(SECRET_FOUND), no comment posted.
  - `FactoryTest.reapprovedItemStartsFromBase` (Review Focus 4): report on attempt 1, then `Approved` via `Transitions.applyExternal`, drive again → new attempt 1's `inputBundle` is `base.bundle`, its feedback empty.
  - `FactoryTest.fileReferenceWithoutBaseFileListIsInvalid` (Review Focus 2).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** In `Factory.build`, after the `build_attempt` event: `Valid` → event `spec_problems` `{problems: [ {requirement, files, explanation} ]}` and `new Step(new Trigger.SpecProblemReported(), null, SpecProblemComment.render("builder", r))`; `Invalid` → event `spec_problems_invalid {reason}` and continue. Prompt: add the section "When the spec cannot be satisfied" from spec §1 with the file path and JSON shape.
- [ ] **Step 4: Run** `-Dtest='FactoryTest,ContainerBuildRunnerTest,SpecProblemCommentTest,BuilderPromptTest'` — PASS.
- [ ] **Step 5: Commit** `feat: the builder can report a spec problem, sending the issue back to draft`.

### Task 5: Examiner reports a spec problem

**Files:**
- Modify: `seal/ExaminerOutput.java` (add `SpecProblems.Read specProblems`), `seal/Examiner.java`, `seal/SuiteSealer.java` (`Examination` gains `SpecProblems specProblems`, nullable), `seal/ApprovalSealer.java`, `lifecycle/SealRejection.java` (`SPEC_PROBLEM`), `src/main/resources/prompts/examiner-system.md`
- Test: `ExaminerTest`, `SuiteSealerTest`, `ApprovalSealerTest`

**Interfaces:**
- Consumes: Tasks 1, 2, 4 (`SpecProblemComment.render("Examiner", …)`).
- `Examiner.attempt` reads `attemptDir/out/spec-problems.json` before the "no Java files" failure check; a `Valid` report never throws `ExaminerFailedException`.

- [ ] **Step 1: Tests**
  - `ExaminerTest.reportIsReturnedEvenWithNoJavaFiles`.
  - `SuiteSealerTest.validReportEndsExaminingWithoutChecks`: one Examiner attempt, zero seal-check runs, `examination.specProblems()` non-null; `examiner_attempt` event has `specProblems: n`.
  - `SuiteSealerTest.invalidReportIsIgnored`: checks run as usual.
  - `ApprovalSealerTest.examinerReportRejects`: `assertRejected(outcome, SPEC_PROBLEM, SpecProblemComment.render("Examiner", r), r.problems().size())`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement**; the report's base files come from `ApprovalSealer.baseFiles(itemDir)`, which Task 2 writes before examining. **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat: the Examiner can report a spec problem, rejecting the seal`.

### Task 6: Record what each attempt's failure was

**Files:**
- Modify: `factory/Factory.java` (`build`, `measure`), `feedback/FeedbackInput.java` (`BuildFailed` gains `List<String> lockedTestsFailed`; 1-arg constructor kept), `feedback/FeedbackRenderer.java`, `measure/TestInventory.java`
- Test: `TestInventoryTest`, `FeedbackRendererTest`, `FactoryTest`

**Interfaces:**
- Produces:
  - `TestInventory.failedLocked(List<String> baselinePassed, List<TestOutcome> outcomes, Collection<String> changeablePaths) -> List<String>` (passed at baseline, FAILED/TIMED_OUT now, file not changeable; baseline order).
  - `TestInventory.sourcePath(String id)` made public.
  - Event fields: `build_attempt.lastMessage` (builder `resultText`, last 10 lines, at most 1,000 chars, only when no commits); `measurement.violation` (first contract-violation message, cut to 300); `measurement.lockedTestsFailed` (list, only when the build failed and some failed).
- Builder feedback for `BuildFailed` with a non-empty list appends exactly: "\n\nThese failing tests are locked: `a`, `b`. If the spec cannot be met without changing them, write `/work/SPEC-PROBLEMS.json` (see the prompt) instead of working around them.\n"

- [ ] **Step 1: Tests** — `TestInventoryTest.failedLockedListsLockedFailures`; `FeedbackRendererTest.buildFailedNamesLockedTests` (exact text); `FactoryTest.failureDataIsRecordedPerAttempt` (locked-test failure on attempt 1 → `measurement.lockedTestsFailed`; contract violation → `measurement.violation`; NO_OUTPUT → `build_attempt.lastMessage`).
- [ ] **Step 2–4:** run FAIL, implement (Factory post-processes a `BuildFailed` shortfall when a baseline exists), run PASS.
- [ ] **Step 5: Commit** `feat: record each attempt's failure detail and name failing locked tests`.

### Task 7: "Why it failed" in the draft PR and failure comment

**Files:**
- Create: `src/main/java/org/jwcarman/noir/factory/FailureExplanation.java`
- Modify: `factory/PullRequestBody.java` (`render` and `failureComment` take `String whyFailed`, nullable; inserted after the shortfall line), `factory/Factory.java` (`draftOrComment`, `body`)
- Test: `FailureExplanationTest`, `PullRequestBodyTest`, `FactoryTest`

**Interfaces:**
- Consumes: Task 6 event fields; `QualityBar` via `Factory.bar(spec)`.
- Produces: `FailureExplanation.render(List<Event> events, QualityBar bar, Map<String,String> lockedTestFiles) -> String` — section `### Why it failed`, one line per attempt per the spec §3 table, consecutive identical lines collapsed ("Attempts 2–4: …"), then the locked-tests paragraph (spec text verbatim; tests sorted and capped at 20; files de-duplicated via `TestInventory.sourcePath`). Category per attempt comes from that attempt's last `transition` event with a `category`.
- QUALITY_SHORTFALL line: "quality shortfall: line coverage 72.0% (bar 90.0%), mutation score 50.0% (bar 80.0%), max CRAP 31.0 (bar 15)" listing only failing metrics.

- [ ] **Step 1: Tests** — `FailureExplanationTest` exact text for each category, collapsing (Review Focus 5), caps, quoting of the last message as `> ` lines prefixed "The builder's last message:"; `PullRequestBodyTest.draftBodyCarriesWhyItFailed` and `failureCommentCarriesWhyItFailed`; `FactoryTest.failedItemExplainsItself` (#77 shape: BUILD_FAILED with locked tests, then three NO_OUTPUT → comment contains the locked test file and "Attempts 2–4").
- [ ] **Step 2–4:** FAIL, implement, PASS. **Step 5: Commit** `feat: failed items explain why they failed`.

### Task 8: Builder prompt states the real acceptance gate

**Files:**
- Modify: `build/BuilderPrompt.java` (`system(boolean hiddenTests)`), `build/BuildRunner.java` and `ContainerBuildRunner.java` (pass it through; add `boolean hiddenTests` parameter), `factory/Factory.java` (from the commitment's file list), `prompts/builder-system.md` (split "Acceptance" into two resource snippets: `builder-acceptance-hidden.md`, `builder-acceptance-off.md`)
- Test: `BuilderPromptTest`

- Off text: "Your work is accepted when `mvn -B verify` passes, every test that passed before your change still passes, and the quality bar is met. There are no hidden tests for this item, so your own tests are the only evidence that every requirement and example in `SPEC.md` is met: test each one."

- [ ] **Step 1: Test** both variants (off contains "There are no hidden tests"; hidden contains "hidden acceptance tests"). **Step 2–4.** **Step 5: Commit** `fix: the builder prompt names the acceptance gate the item really has`.

### Task 9: End-to-end tests and docs

**Files:**
- Create fixtures: `src/test/resources/fixtures/modify/builders/reports-spec-problem/attempt-1/` (no repo changes; writes `../SPEC-PROBLEMS.json` naming R1 and `src/test/java/com/example/api/CounterTest.java`), `…/breaks-locked-test-then-quits/attempt-1` (Counter capping at 2, breaking a base CounterTest case) with later attempts writing nothing.
- Modify: `metrics/ExperimentMetrics.java` (a `specProblems` total: items with a `spec_problems` event; `spec_problem` rejections are already counted by reason), `factory/PipelineIT.java`, `README.md` (seal-rejection list gains `spec_problem`; the Spec and Deliver steps mention reports and "Why it failed"), `docs/findings/2026-11-07-overnight-observations.md` (mark Improvements 6, 9, 10, 13 addressed).

- [ ] **Step 1: Tests** — `PipelineIT.builderSpecProblemSendsIssueToDraft` (path `SEALING > SEALED > BUILDING > DRAFT`, comment text, no PR); `PipelineIT.failedItemNamesTheLockedTest` (draft PR body contains "Existing tests that failed" and `CounterTest.java`). The copying builder must be able to write outside the repo: extend the fixture copier to place a top-level `SPEC-PROBLEMS.json` at the attempt dir root.
- [ ] **Step 2–4:** `mvn -B -Pdocker test -Dtest=PipelineIT` FAIL → implement → PASS; then `mvn -B verify` and `mvn -B -Pdocker verify` both green.
- [ ] **Step 5: Commit** `test: end-to-end spec problems and failure explanations; docs`.
