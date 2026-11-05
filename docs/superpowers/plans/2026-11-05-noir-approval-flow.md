# noir approval flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move sealing from the desktop into `noir run`, triggered by a human adding `noir:ready` to an issue whose body is the spec, so the noir server and the desktop share nothing but GitHub.

**Architecture:** A strict parser makes the issue body the spec (`spec/IssueSpec`). The lifecycle gains `DRAFT` and `SEALING`; the daemon creates a work item when it sees `noir:ready` on an issue, and `Factory` runs a new `SEALING` phase (`seal/ApprovalSealer`) that lock-checks, parses, clones, examines and commits — reusing v1's Examiner and seal checks — before the unchanged build pipeline. The desktop keeps only `noir spec check|template` and the rewritten `/noir-spec` skill.

**Tech Stack:** Java 21, Maven, picocli, Jackson, JUnit 5 + AssertJ, `gh` CLI (incl. `gh api graphql`), git, Docker.

**Spec:** `docs/superpowers/specs/2026-11-05-noir-approval-flow-design.md` (builds on `docs/superpowers/specs/2026-11-03-noir-v1-design.md`).

## Global Constraints

- Java 21; no new dependencies. Spotless (google-java-format) must pass: run `mvn -B -q spotless:apply` before committing.
- Event data never contains nulls (`Event` uses `Map.copyOf`): omit absent keys.
- Every text noir sends to GitHub (comments, labels, bodies) passes the existing outbound secret check; errors shown to users go through `Secrets.describe`/`Secrets.redact`.
- No comment, label or issue body ever contains holdout test names, test source, or seal-check log output.
- `noir:ready` is never added by noir and never stripped by reconciliation.
- One work item per (repo, issue), ever.
- Unit suite: `mvn -B verify`. Docker suite: `mvn -B -Pdocker verify`. Both green at the end of every task that touches code they cover.
- Commits end with the session's attribution trailer; subagents use their own model's `Co-Authored-By` line.

## Review Focus

1. **Issue bodies edited in the GitHub web UI come back with `\r\n` line endings** — the parser must normalize to `\n` before parsing and hashing (Task 1 test `parsesCrlfBody`; Task 5 hashes the normalized body).
2. **A title rename after approval is an edit** — GitHub's `lastEditedAt` covers the body only; the lock check must also take the latest `RenamedTitleEvent` (Task 2 test `editedAtIncludesTitleRename`, Task 5 test `titleRenameAfterApprovalRejects`).
3. **`noir:ready` removed and re-added** — the latest `LabeledEvent` for `noir:ready` is the approval; earlier ones are ignored (Task 2 test `latestReadyLabelWins`).
4. **An issue whose item is already terminal gets `noir:ready` again** (reopened or re-labelled) — no second item; warn once per daemon run (Task 6 test `terminalItemIsNeverRecreated`).
5. **The daemon dies between creating an item and finishing the seal** — the next pass resumes `SEALING` for that item instead of creating another (Task 6 test `crashAfterItemCreationResumesSealing`).

## Spec deviation (ruled before execution)

- Spec §6 gives "R3: no test failed on the stubs" as an example rejection line. Seal-check problems name holdout tests, and mapping them to requirements is not exposed, so the public comment uses **fixed category phrases with counts** (Task 5, `SealProblems.publicSummary`) and no requirement ids. Cost if wrong: less specific feedback on weak suites.

---

### Task 1: Issue-body format — renderer and strict parser

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/render/SpecRenderer.java`
- Create: `src/main/java/org/jwcarman/noir/spec/IssueSpec.java`, `src/main/java/org/jwcarman/noir/spec/IssueSpecException.java`, `src/main/java/org/jwcarman/noir/spec/StubLint.java`
- Modify: `src/main/java/org/jwcarman/noir/spec/SpecLint.java`
- Test: `src/test/java/org/jwcarman/noir/render/SpecRendererTest.java`, `src/test/java/org/jwcarman/noir/spec/IssueSpecTest.java`, `src/test/java/org/jwcarman/noir/spec/SpecLintTest.java`, `src/test/java/org/jwcarman/noir/spec/StubLintTest.java`

**Interfaces:**
- Produces:
  - `SpecRenderer.issueBody(Spec, List<StubSource>) -> String`: the §4 sections from `## Intent` to the end of `## Quality bar`, then `\n---\n\n` + `SpecRenderer.FOOTER`. **No `# title` line.** `FOOTER = "_noir spec · add \`noir:ready\` to approve · edits after sealing are ignored_"`.
  - `SpecRenderer.specBody(Spec, List<StubSource>)` = `"# " + title + "\n\n"` + the same sections (builder view unchanged).
  - `SpecRenderer.template(String title) -> String`: an `issueBody` for a skeleton spec with `TODO` placeholders (one stub `src/main/java/TODO.java`, one requirement `R1` with one example).
  - `record IssueSpec(String title, String intent, List<Requirement> requirements, List<String> constraints, QualityBar qualityBar, List<StubSource> stubs)` with `static IssueSpec parse(String title, String body)` (throws `IssueSpecException`) and `Spec toSpec(String id, String repo, String baseSha)` (schemaVersion 1, `apiStubs` = stub paths in order).
  - `static String IssueSpec.normalize(String body)`: `\r\n`→`\n`, trailing whitespace on the whole body trimmed to a single final `\n`.
  - `IssueSpecException extends RuntimeException` with `List<String> problems()`; each problem formatted `"line <n>: <message>"`.
  - `SpecLint.lintContent(Spec) -> List<String>`: every existing rule except schemaVersion, id, repo and baseSha. `SpecLint.lint(Spec)` = those four + `lintContent`.
  - `StubLint.lint(List<StubSource>) -> List<String>`: the stub rules moved out of `DraftCheck` (path is relative, under `src/main/java/`, ends `.java`, no `..`; declared package matches the path; no duplicate paths).
  - `IssueSpec.lint(IssueSpec) -> List<String>` = `SpecLint.lintContent(toSpec(placeholder…))` + `StubLint.lint(stubs)`, also rejecting any field containing `TODO`. Placeholder id/repo/baseSha are private constants and never appear in messages.

Parser grammar (normalized body, line-based):
- Sections are `## Intent`, `## API contract`, `## Requirements`, `## Constraints`, `## Quality bar`, exactly once each, in that order; anything before `## Intent` other than blank lines is an error.
- `## API contract`: one or more `### <path>` headings, each followed (blank lines allowed) by an opening fence of 3+ backticks immediately followed by `java`, content, and a closing line equal to the opening fence's backticks. Unclosed fence → error at the opening line.
- `## Requirements`: `### R<n>` headings; statement = the paragraph lines before the first example bullet, joined with `\n`, trimmed; examples are lines `- **Given** <g> **when** <w> **then** <t>` (each part non-empty, trimmed).
- `## Constraints`: `- <text>` bullets, or the single line `_None._` (→ empty list).
- `## Quality bar`: exactly the three bullet lines `- Line coverage ≥ <int>%`, `- Mutation score ≥ <int>%`, `- Max CRAP score per method ≤ <int>`; percentages become fractions (`80` → `0.80`).
- Footer: if the last non-blank line starts with `_noir` and the line before it (skipping blanks) is `---`, both are dropped before parsing.
- All problems are collected (do not stop at the first) except that an unclosed fence stops parsing of the rest.

- [ ] **Step 1: Write the failing tests**

`IssueSpecTest`:
```java
@Test void roundTripsEveryFixture() {
  for (var spec : Specs.all()) {               // add Specs.all() returning the existing fixture specs
    var stubs = Specs.stubsFor(spec);
    var parsed = IssueSpec.parse(spec.title(), SpecRenderer.issueBody(spec, stubs));
    assertThat(parsed.toSpec(spec.id(), spec.repo(), spec.baseSha())).isEqualTo(spec);
    assertThat(parsed.stubs()).isEqualTo(stubs);
  }
}
@Test void parsesCrlfBody() {
  var body = SpecRenderer.issueBody(Specs.counter(), Specs.stubsFor(Specs.counter())).replace("\n", "\r\n");
  assertThat(IssueSpec.parse("Add a Counter", body).requirements()).hasSize(Specs.counter().requirements().size());
}
@Test void footerIsOptional() { /* body without the --- footer parses to the same IssueSpec */ }
@Test void missingSectionReportsLine() {
  var ex = catchThrowableOfType(() -> IssueSpec.parse("t", bodyWithout("## Constraints")), IssueSpecException.class);
  assertThat(ex.problems()).anyMatch(p -> p.matches("line \\d+: missing section ## Constraints"));
}
@Test void sectionsOutOfOrder() { /* "line N: ## Requirements must come after ## API contract" */ }
@Test void unclosedFence() { /* "line N: code fence opened here is never closed" where N is the fence line */ }
@Test void exampleWithoutThen() { /* "line N: example must be - **Given** … **when** … **then** …" */ }
@Test void badQualityBar() { /* "- Line coverage ≥ eighty%" → "line N: expected - Line coverage ≥ <n>%" */ }
@Test void constraintsNone() { /* "_None._" → constraints().isEmpty() */ }
@Test void textBeforeIntent() { /* "# Add a Counter" first line → "line 1: unexpected text before ## Intent" */ }
@Test void lintRejectsTodo() {
  var parsed = IssueSpec.parse("Add X", SpecRenderer.template("Add X"));
  assertThat(IssueSpec.lint(parsed)).anyMatch(p -> p.contains("TODO"));
}
@Test void lintNeverMentionsPlaceholders() { /* lint of a spec with a bad requirement id: no message contains the placeholder id/repo/sha */ }
```
`SpecRendererTest`: `issueBodyHasNoTitleHeadingAndEndsWithFooter`, `specBodyStillStartsWithTitle`.
`SpecLintTest`: `lintContentSkipsIdentityRules` (a spec with null id/repo/baseSha has no lintContent problems).
`StubLintTest`: move the stub-rule cases from `DraftCheckTest` (package mismatch, outside `src/main/java`, `..`, duplicate path).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -B -q test -Dtest='IssueSpecTest,SpecRendererTest,SpecLintTest,StubLintTest'`
Expected: compilation failure (`IssueSpec`, `StubLint`, `lintContent` do not exist).

- [ ] **Step 3: Implement** per the Interfaces block. `DraftCheck` calls `StubLint.lint` for its stub rules (it is deleted in Task 7). Existing callers of `issueBody` (the old `Sealer`) keep compiling.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -B -q spotless:apply && mvn -B verify`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit** — `feat: strict issue-body spec format with round-trip parser`

---

### Task 2: GitHub port — issue snapshots, labels, repo clone

**Files:**
- Create: `src/main/java/org/jwcarman/noir/github/IssueSnapshot.java`
- Modify: `src/main/java/org/jwcarman/noir/github/GitHubPort.java`, `GhCli.java`, `NoirLabels.java`; `src/main/java/org/jwcarman/noir/git/GitPort.java`, `GitCli.java`
- Test: `src/test/java/org/jwcarman/noir/github/GhCliTest.java`, `FakeGitHub.java`, `src/test/java/org/jwcarman/noir/git/GitCliTest.java`

**Interfaces:**
- Produces:
  - `record IssueSnapshot(int number, String title, String body, boolean open, Instant createdAt, Instant editedAt, Optional<Approval> approval)` where `editedAt` = max(`createdAt`, `lastEditedAt`, latest `RenamedTitleEvent.createdAt`), and `record Approval(String actor, Instant at)` is the latest `LabeledEvent` whose label is `noir:ready` **and** the label is currently on the issue (else empty). `body` is returned raw (normalization is the caller's).
  - `GitHubPort.issue(String repo, int number) -> IssueSnapshot`.
  - `GitHubPort.removeLabel(String repo, int number, String label)` — no error if absent.
  - `NoirLabels.READY = "noir:ready"`; `NoirLabels.ALL` adds `noir:draft` (`"c5def5"`, "Spec draft; add noir:ready to approve") and `noir:sealing` (`"fbca04"`, "noir is writing the hidden test suite"); `noir:ready`'s description becomes "Approved by a human; noir will seal and build". New `NoirLabels.STATE` = `ALL` minus `READY`.
  - `setNoirLabel` removes only `NoirLabels.STATE` labels (never `noir:ready`).
  - `GitPort.cloneRepo(String repo, Path dir)` → `gh repo clone <repo> <dir> -- --quiet` (via the command runner, so `gh` auth is used); `GitPort.exists(Path repoDir, String sha, String path) -> boolean` → `git cat-file -e <sha>:<path>` (exit 0 → true, 128 → false, else `GitException`).
  - `FakeGitHub` gains: `issue(...)` from its stored issues, `editIssue(int n, String title, String body, Instant at)`, `addLabel(int n, String label, String actor, Instant at)`, `removeLabel`, `labels(int n) -> Set<String>`, and records comments per issue (`comments(int n) -> List<String>`).

`GhCli.issue` runs one `gh api graphql` query with `-F owner=… -F name=… -F number=…`:
```graphql
query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){issue(number:$number){
  number title body state createdAt lastEditedAt labels(first:100){nodes{name}}
  timelineItems(last:100,itemTypes:[LABELED_EVENT,RENAMED_TITLE_EVENT]){nodes{
    __typename ... on LabeledEvent{createdAt actor{login} label{name}} ... on RenamedTitleEvent{createdAt}}}}}}
```
Missing `issue` or non-JSON output → `GitHubException`; `actor` null (deleted user) → `"ghost"`.

- [ ] **Step 1: Write the failing tests** (`GhCliTest`, using the existing `FakeCommandRunner` canned-output pattern):
  - `issueParsesSnapshot` (title, body, open, createdAt, approval actor/at)
  - `editedAtIncludesTitleRename` (rename later than `lastEditedAt` → `editedAt` = rename time)
  - `latestReadyLabelWins` (two `noir:ready` LabeledEvents → the later one)
  - `approvalEmptyWhenReadyLabelNotPresent` (event exists but label since removed)
  - `issueArgvUsesGraphqlWithVariables` (exact argv)
  - `setNoirLabelNeverRemovesReady` (removed list excludes `noir:ready`)
  - `removeLabelArgv`
  - `GitCliTest`: `cloneRepoUsesGh`, `existsTrueFalseAndError`.

- [ ] **Step 2: Run to verify failure** — `mvn -B -q test -Dtest='GhCliTest,GitCliTest'` → compilation failure.
- [ ] **Step 3: Implement**, including `FakeGitHub` additions (every test double implementing `GitHubPort`/`GitPort` gains the new methods).
- [ ] **Step 4: Verify** — `mvn -B -q spotless:apply && mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat: issue snapshots with approval and edit times; noir:draft/noir:sealing labels`

---

### Task 3: `noir spec check|template`

**Files:**
- Create: `src/main/java/org/jwcarman/noir/cli/SpecCommand.java`
- Modify: `src/main/java/org/jwcarman/noir/cli/NoirCli.java` (register)
- Test: `src/test/java/org/jwcarman/noir/cli/SpecCommandTest.java`

**Interfaces:**
- Consumes: `IssueSpec.parse/lint/normalize`, `SpecRenderer.template` (Task 1); `GitHubPort.issue` (Task 2) via `Operations.github()`.
- Produces: `noir spec check <file> [--title <t>]`, `noir spec check --issue <owner/repo>#<n>`, `noir spec template --title <t>`.

Behavior:
- `check <file>`: title from `--title`, required with a file (picocli error otherwise). Output `OK` and exit 0 when parse and lint are clean; otherwise each problem on its own line to stdout and exit 1.
- `check --issue owner/repo#n`: uses the issue's title and body; additionally fails with `issue is closed` if not open. Malformed reference → exit 2 with `expected owner/repo#number`.
- `template --title <t>`: prints `SpecRenderer.template(t)`.
- GitHub errors print `Secrets.describe(...)` and exit 1. Never needs Docker or credentials.

- [ ] **Step 1: Failing tests** — `checkCleanFilePrintsOk`, `checkReportsEveryProblemAndExits1`, `checkFileRequiresTitle`, `checkIssueUsesTitleAndBody` (FakeGitHub), `checkClosedIssueFails`, `checkBadIssueReference`, `templatePassesParseButFailsLintOnTodo`.
- [ ] **Step 2: Verify failure** — `mvn -B -q test -Dtest=SpecCommandTest` → compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify** — `mvn -B -q spotless:apply && mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat: noir spec check and template`

---

### Task 4: Lifecycle — DRAFT, SEALING and seal triggers; commitment v2

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/lifecycle/{State,Trigger,SideEffect,Lifecycle}.java`, `src/main/java/org/jwcarman/noir/store/WorkItem.java`, `src/main/java/org/jwcarman/noir/factory/Transitions.java`, `src/main/java/org/jwcarman/noir/commit/{Commitment,Commitments}.java`
- Test: `src/test/java/org/jwcarman/noir/lifecycle/{LifecycleTest,StateTest}.java`, `src/test/java/org/jwcarman/noir/factory/TransitionsTest.java`, `src/test/java/org/jwcarman/noir/commit/CommitmentsTest.java`

**Interfaces:**
- Produces:
  - `State.DRAFT("noir:draft")`, `State.SEALING("noir:sealing")`; `active()` now includes `SEALING`; `SEALED` label becomes `"noir:building"`.
  - `enum SealRejection { EDITED_AFTER_APPROVAL, INVALID_SPEC, SUITE_TOO_WEAK, STUB_EXISTS }` (in `lifecycle`); event value = `name().toLowerCase()`.
  - Triggers: `Approved()`, `SealPassed()`, `SealRejected(SealRejection reason)`.
  - `SideEffect.REJECT_APPROVAL` (comment + remove `noir:ready`; performed by Factory in Task 6).
  - Lifecycle rules: `DRAFT + Approved → SEALING [APPLY_LABEL]`; `SEALING + SealPassed → SEALED [APPLY_LABEL]`; `SEALING + SealRejected → DRAFT [REJECT_APPROVAL, APPLY_LABEL]`; `SEALING + InfraBlocked → BLOCKED(INFRASTRUCTURE, blockedPhase SEALING)` (falls out of `active()`); `ResumeRequested` from that block → `SEALING`. `Pickup` from `SEALED`/`REWORK` unchanged. `IssueClosed`/`CancelRequested` from `DRAFT`/`SEALING` → `CANCELLED`.
  - `WorkItem.approved(String id, String repo, int issueNumber)` → state `SEALING`, attempt 0.
  - `Transitions.persist` adds `"reason"` to transition data for `SealRejected`.
  - `Commitment` gains `String issueBodySha256` (after `specSha256`); `Commitments.VERSION = 2`; `Commitments.create(Spec, Path itemDir, String examinerModel, String issueBodySha256)`. Reading a v1 commitment (no field) still works (field null on read is allowed only for version 1).

- [ ] **Step 1: Failing tests** — one `LifecycleTest` case per rule above, plus `draftIsNeitherActiveNorTerminal`, `sealingIsActive`, `sealRejectedRecordsReason` (Transitions), `commitmentV2IncludesBodyHash`, `readsV1Commitment`.
- [ ] **Step 2: Verify failure** — `mvn -B -q test -Dtest='LifecycleTest,StateTest,TransitionsTest,CommitmentsTest'`.
- [ ] **Step 3: Implement.** The old desktop `Sealer` passes `issueBodySha256` = SHA-256 of its rendered issue body until Task 7 removes it.
- [ ] **Step 4: Verify** — `mvn -B -q spotless:apply && mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat: DRAFT and SEALING states, seal triggers, commitment v2`

---

### Task 5: `ApprovalSealer` — the SEALING phase

**Files:**
- Create: `src/main/java/org/jwcarman/noir/seal/ApprovalSealer.java`, `SealOutcome.java`, `SealProblems.java`, `SuiteSealer.java`, `ApprovalComments.java`; `src/main/java/org/jwcarman/noir/git/RepoMirror.java`
- Modify: `src/main/java/org/jwcarman/noir/seal/Sealer.java` (delegate examine/checkSuite to `SuiteSealer`), `src/main/java/org/jwcarman/noir/NoirHome.java` (`repos()` = `<home>/repos`)
- Test: `src/test/java/org/jwcarman/noir/seal/{ApprovalSealerTest,SealProblemsTest,SuiteSealerTest}.java`, `src/test/java/org/jwcarman/noir/git/RepoMirrorTest.java`

**Interfaces:**
- Consumes: `IssueSpec` (T1); `GitHubPort.issue`, `GitPort.cloneRepo/exists` (T2); `SealRejection`, `Commitments.create(…, bodySha)` (T4); existing `Examiner`, `SealCheckRunner`, `SealJudge`, `HoldoutAnalyzer`.
- Produces:
  - `SuiteSealer(Store, Examiner, SealCheckRunner, NoirConfig, Clock)` with `Examination examine(Spec, List<StubSource>, Path itemDir, Path bundle)`; `record Examination(String model, List<String> problems)` (problems null on success). Moved verbatim from `Sealer` (events `examiner_attempt` unchanged, appended to `itemDir`).
  - `RepoMirror(GitPort git, Path reposRoot)` with `Path sync(String repo, String branch)`: clone into `reposRoot/<owner>/<name>` if absent, then `fetch(branch)`; returns the clone dir.
  - `sealed interface SealOutcome { record Passed(String commitmentSha256) ; record Rejected(SealRejection reason, String comment) }`.
  - `ApprovalSealer(Store, GitHubPort, RepoMirror, GitPort, SuiteSealer, Clock, Set<String> secrets)` with `SealOutcome seal(WorkItem item)`.
  - `SealProblems.publicSummary(List<String> problems) -> List<String>`: maps each problem to a fixed phrase, deduplicated with counts, in this order: `"the API stubs do not compile"`, `"the hidden tests do not compile against the stubs"`, `"no hidden tests were found"`, `"<n> hidden test(s) passed against the stubs"`, `"the reference implementation does not compile"`, `"the reference implementation failed the hidden tests"`, `"mutation score below the required threshold"`, `"the hidden tests did not meet noir's structural rules"` (any `HoldoutAnalyzer` problem), `"a seal check did not complete"`; repository-shape problems (`pom.xml not found…`, `multi-module…`, `maven.compiler.release must be 21…`) are passed through verbatim (they contain no test data). Anything unmatched → `"other check failures"`. The input strings are never copied otherwise.
  - `ApprovalComments`: exact texts —
    - `editedAfterApproval()` = `"noir did not seal this issue: it was edited after \`noir:ready\` was added. Please review the current text and add \`noir:ready\` again."`
    - `invalidSpec(List<String> problems)` = `"noir could not read this issue as a spec:\n\n" + "- " + each problem + "\n" … + "\nFix the issue body (\`noir spec check --issue <repo>#<n>\` shows the same list), then add \`noir:ready\` again."`
    - `stubExists(List<String> paths)` = `"noir did not seal this issue: these API stub files already exist on the default branch:\n\n- …\n\nStubs may only add new files. Revise the spec, then add \`noir:ready\` again."`
    - `suiteTooWeak(int attempts, List<String> summary)` = `"noir could not produce a hidden test suite that passes its checks after " + attempts + " attempts:\n\n- …\n\nThis usually means a requirement is ambiguous or untestable as written. Revise the spec, then add \`noir:ready\` again."`
    - `sealed(String sha, String baseSha, String bodySha)` = `"noir commitment \`" + sha + "\` (commitmentVersion 2, base \`" + baseSha + "\`, issue body sha256 \`" + bodySha + "\`)"`.

`ApprovalSealer.seal(item)`, in order (item dir = `store.itemDir(item.id())`):
1. `snapshot = gh.issue(repo, issue)`. If `commitment.json` exists in the item dir → skip to step 8 (crash recovery).
2. Lock: `approval` empty or `snapshot.editedAt().isAfter(approval.at())` → `Rejected(EDITED_AFTER_APPROVAL, editedAfterApproval())`. Else append `approved` event `{approver, approvedAt, issueCreatedAt, bodySha256, title}` where `bodySha256` = SHA-256 hex of `IssueSpec.normalize(body)` UTF-8.
3. Parse + lint: `IssueSpecException` or non-empty `IssueSpec.lint` → `Rejected(INVALID_SPEC, invalidSpec(problems))`.
4. `branch = gh.defaultBranch(repo)`; `dir = mirror.sync(repo, branch)`; `baseSha = git.revParse(dir, "origin/" + branch)`.
5. Stubs whose path `git.exists(dir, baseSha, path)` → `Rejected(STUB_EXISTS, stubExists(paths))`.
6. Write `spec.json` (`IssueSpec.toSpec(item.id(), repo, baseSha)`), `stubs/<path>` files, `base.bundle` (`git.bundle`).
7. `examination = suiteSealer.examine(...)`; problems → `Rejected(SUITE_TOO_WEAK, suiteTooWeak(config attempts, publicSummary(problems)))`. Else `Commitments.write(create(spec, itemDir, model, bodySha), itemDir/commitment.json)`.
8. Post `sealed(...)` comment unless a `commitment_posted` event exists; then append `commitment_posted`, then the `sealed` event `{issue, commitmentSha256, baseSha, bodySha256}`; return `Passed(sha)`.
- Before every rejection, delete any partial `spec.json`, `stubs/`, `base.bundle`, `holdout/`, `reference/`, `commitment.json` in the item dir (examiner attempt dirs stay as history); append `seal_rejected {reason, problems: <count>}`.
- Comments go out through the existing outbound secret check (`Secrets` with `secrets`).

- [ ] **Step 1: Failing tests** (`ApprovalSealerTest`, FakeGitHub + fake GitPort + a stub `SuiteSealer` or `ScriptedAgent`-backed Examiner as `SealerTest` does today):
  - `cleanApprovalSealsAndPostsCommitment` (comment text exact; events `approved`, `commitment_posted`, `sealed`; `spec.json` baseSha = mirror head)
  - `editAfterApprovalRejects`, `titleRenameAfterApprovalRejects`, `missingApprovalRejects`
  - `invalidBodyRejectsWithEveryProblem`
  - `existingStubPathRejects`
  - `weakSuiteCommentHasNoTestNames` (feed problems containing `holdout test passes against the stubs (vacuous): nr.Foo#bar`; assert comment contains `1 hidden test passed against the stubs` and not `Foo`)
  - `rejectionCleansPartialFiles`
  - `crashAfterCommitmentWrittenFinishesWithoutExamining` (commitment.json present → examiner never called; comment posted once)
  - `crashAfterCommentDoesNotRepost`
  - `crlfBodyHashesNormalized`
  - `SealProblemsTest`: one case per phrase + passthrough + `other check failures`.
  - `RepoMirrorTest`: `clonesOnceThenFetches`.
  - `SuiteSealerTest`: move the examine/attempt cases from `SealerTest`.
- [ ] **Step 2: Verify failure** — `mvn -B -q test -Dtest='ApprovalSealerTest,SealProblemsTest,RepoMirrorTest,SuiteSealerTest'`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify** — `mvn -B -q spotless:apply && mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat: ApprovalSealer seals an approved issue on the server`

---

### Task 6: Daemon, Factory and Reconciler integration

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/factory/{Factory,Daemon,Reconciler,Delivery,GitHubDelivery}.java`, `src/main/java/org/jwcarman/noir/cli/Wiring.java`
- Test: `src/test/java/org/jwcarman/noir/factory/{FactoryTest,DaemonTest,ReconcilerTest,GitHubDeliveryTest}.java`

**Interfaces:**
- Consumes: everything from Tasks 2, 4, 5.
- Produces:
  - `Factory` takes an `ApprovalSealer` (new constructor parameter after `delivery`). `advance` on `SEALING` runs `approvalSealer.seal(item)` **before** reading `spec.json`: `Passed` → `SealPassed`; `Rejected(r, c)` → `SealRejected(r)` with `c` carried in the `Step` to `perform`. `InfraFailure` (and `DockerException`/`GitException`/`GitHubException` thrown by the sealer, wrapped by the existing `Retry`) → `InfraBlocked`.
  - `perform` handles `REJECT_APPROVAL`: `delivery.rejectApproval(item, comment)` = comment then `gh.removeLabel(repo, issue, NoirLabels.READY)`.
  - `Delivery.rejectApproval(WorkItem, String comment)`.
  - `Daemon` per pass, after reconciliation and dispositions, for each configured repo: `ready = gh.openIssuesWithLabel(repo, READY)` (always, not only when SEALED items exist). For each issue number:
    - no item for (repo, issue) → create one: new id `ItemId.newId(clock, rnd)`, `store.writeWorkItem(WorkItem.approved(...))`, event `item_created {issue}`; report via a new `Daemon.Listener.created(String itemId, String repo, int issue)`; `RunCommand`'s printer prints `<id>  <repo>#<n>  approved → SEALING`;
    - item in `DRAFT` → apply `Approved` via `Transitions.applyExternal`;
    - item terminal → `listener.warning("<repo>#<n> has noir:ready but its work item <id> is <STATE>; open a new issue")` once per run;
    - otherwise nothing.
  - Eligible items: `SEALING`, `SEALED` (no label check any more), `REWORK`, and active. `DRAFT` is never driven.
  - The `Store` lookup by (repo, issue): `Store.findItem(String repo, int issue) -> Optional<String>` (scan `workitem.json`s; unreadable items skipped).
  - `Reconciler` uses `gh.issue(...)` once per non-terminal item: closed → `IssueClosed`; else re-apply the state label; and if the item has a `sealed` event, `snapshot.editedAt()` is after it, and no `edit_notice` event exists → comment `"noir: this issue was edited after it was sealed. The edits do not affect the build in progress; to change the spec, close this issue and open a new one."` and append `edit_notice`.

- [ ] **Step 1: Failing tests**
  - `DaemonTest`: `readyIssueCreatesItemAndSeals`, `readyIssueInUnconfiguredRepoIgnored`, `draftItemReapprovedMovesToSealing`, `terminalItemIsNeverRecreated` (one warning across two passes), `crashAfterItemCreationResumesSealing` (item exists in SEALING, no new item, sealer called), `draftIsNeverDriven`, `sealedItemBuildsWithoutReadyLabel`.
  - `FactoryTest`: `sealingPassedMovesToSealed`, `sealingRejectedCommentsRemovesReadyAndDrafts` (label `noir:draft`, `noir:ready` gone, comment exact), `sealingInfraFailureBlocks`, `resumeBlockedSealingReseals`.
  - `ReconcilerTest`: `draftAndSealingLabelsApplied`, `reconcileNeverStripsReady`, `editAfterSealPostsOneNotice`, `editBeforeSealPostsNoNotice`, `closedDraftCancels`.
- [ ] **Step 2: Verify failure** — `mvn -B -q test -Dtest='DaemonTest,FactoryTest,ReconcilerTest,GitHubDeliveryTest'`.
- [ ] **Step 3: Implement**, wiring `ApprovalSealer` in `Wiring.daemon` (Examiner + `ContainerSealCheckRunner` with the run's docker; `RepoMirror(new GitCli(runner), home.repos())`).
- [ ] **Step 4: Verify** — `mvn -B -q spotless:apply && mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat: noir run picks up approved issues and seals them`

---

### Task 7: Remove the desktop seal flow; metrics

**Files:**
- Delete: `cli/SealCommand.java`, `cli/SealOperation.java`, `cli/DraftCommand.java`, `spec/DraftCheck.java`, `seal/Sealer.java`, `seal/SealProgress.java`, `seal/SealResult.java` (if unused), and their tests (`SealCommandTest`, `DraftCommandTest`, `DraftCheckTest`, `SealerTest`); convert `SealEndToEndIT` to drive `ApprovalSealer` (same scripted Examiner, FakeGitHub issue with `noir:ready`).
- Modify: `cli/NoirCli.java` (drop subcommands; `Operations` loses `sealers`), `cli/Wiring.java`, `store/Store.java` (drop `draftDir`), `NoirHome.java` (drop `drafts()`), `metrics/{ExperimentMetrics,ExperimentReport}.java`, `cli/StatusCommand.java`
- Test: `metrics/ExperimentMetricsTest.java`, `cli/StatusCommandTest.java`, `cli/NoirCliTest.java`

**Interfaces:**
- Produces (metrics): `ExperimentReport` replaces `medianHumanTime` with `medianApprovalTime` (first `approved` event: `approvedAt - issueCreatedAt`), adds `medianSealTime` (first `approved` event ts → `sealed` event ts) and `Map<String,Integer> sealRejections` (by `seal_rejected.reason`, insertion order of the enum). Render lines: `Approval time (median)` → `"<duration> (issue opened → noir:ready)"` or `n/a`; `Seal time (median)`; `Seal rejections` → `"none"` or `"edited_after_approval 1, suite_too_weak 2"`. JSON keys `medianApprovalTime`, `medianSealTime`, `sealRejections`. `draft_created`/`seal_requested` are ignored.

- [ ] **Step 1: Failing tests** — `approvalTimeFromApprovedEvent`, `sealTimeFromApprovedToSealed`, `sealRejectionsByReason`, `oldItemsWithoutApprovedEventShowNa`, `noirCliHasNoSealOrDraftCommands`, status block snapshot updated.
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement and delete.** `grep -rn "draftDir\|drafts()\|SealOperation\|seal_requested\|draft_created" src/main` must return nothing.
- [ ] **Step 4: Verify** — `mvn -B -q spotless:apply && mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `refactor: remove the desktop seal flow; approval and seal metrics`

---

### Task 8: `/noir-spec` skill and README

**Files:**
- Modify: `src/main/resources/skills/noir-spec/SKILL.md`, `README.md`
- Test: `src/test/java/org/jwcarman/noir/cli/NoirSpecSkillTest.java`

**Interfaces:**
- Consumes: `noir spec check|template` (T3); labels from T2.

Skill content (rewrite; keep the existing one-question-at-a-time co-writing guidance, the repo-reading guidance and the "never write into the repo" rule):
1. Preconditions: inside a git clone whose `origin` is a GitHub repo; `gh auth status` ok; `gh label list --repo <repo> --search noir:ready` finds `noir:ready` — else stop: "No noir server watches <repo> (its noir labels are missing)."
2. New spec: draft the body in `$(mktemp -d)/body.md` starting from `noir spec template --title "<title>"`; co-write; `noir spec check <file> --title "<title>"` until `OK`.
3. Show the exact title and body; on explicit approval: `gh issue create --repo <repo> --title "<title>" --body-file <file> --label noir:draft`; print the issue URL.
4. Revision (`/noir-spec #N`): refuse unless the issue is open and labelled `noir:draft` (and not `noir:sealing`/`noir:building`/`noir:pr-open`); `gh issue view N --json title,body`; revise; check; show diff; on approval `gh issue edit N --body-file <file>` (and `--title` if changed).
5. End every run with: "Review issue #N on GitHub and add the `noir:ready` label when you're happy with it. noir will seal and build it from there."
6. Hard rules: never add `noir:ready`; never run `noir run`, `noir audit`, or read `~/.noir`; never needs `ANTHROPIC_API_KEY`.
README: Quick start split into **Server** (config, `noir run`, credentials, Docker) and **Desktop** (`noir skill install`, `/noir-spec`, approve with `noir:ready`); lifecycle line updated to include `DRAFT → SEALING`; "Without the skill" uses `noir spec template` + `gh issue create --label noir:draft`.

- [ ] **Step 1: Failing tests** — update `NoirSpecSkillTest`: `everyNoirCommandInTheSkillParses` (now `spec check|template`), `skillNeverAddsReadyLabel` (no `--add-label noir:ready`/`--label noir:ready` anywhere), `skillMentionsNoCredential` (no `ANTHROPIC_API_KEY` except in the "never needs" rule), `skillNeverReadsNoirHome`, frontmatter test unchanged.
- [ ] **Step 2: Verify failure** — `mvn -B -q test -Dtest=NoirSpecSkillTest`.
- [ ] **Step 3: Rewrite the skill and README.**
- [ ] **Step 4: Verify** — `mvn -B verify` → BUILD SUCCESS.
- [ ] **Step 5: Commit** — `docs: /noir-spec publishes drafts; approval with noir:ready`

---

### Task 9: PipelineIT approval scenario

**Files:**
- Modify: `src/test/java/org/jwcarman/noir/factory/PipelineIT.java` (and `SealedItemFixture` if helpful)

**Interfaces:**
- Consumes: the real `Daemon`/`Factory`/`ApprovalSealer` with real Docker seal checks, `ScriptedAgent` for both Examiner and builder, FakeGitHub, a local bare origin standing in for the mirror's clone source (inject a `GitPort` whose `cloneRepo` clones the bare repo path).

- [ ] **Step 1: Write the scenario** `approvedIssue`: FakeGitHub issue with the Counter body (rendered by `SpecRenderer.issueBody`) and `noir:ready` added after the last edit; scripted Examiner returns the existing Counter holdout/reference fixtures; scripted builder writes the `correct` fixture. Expected path printed and asserted: `SEALING > SEALED > BUILDING > MEASURING > GRADING > PR_OPEN`; one commitment comment; `noir:ready` still on the issue.
- [ ] **Step 2: Add** `approvedIssueEditedAfterApproval`: expected `SEALING > DRAFT`, comment = `ApprovalComments.editedAfterApproval()`, `noir:ready` removed, Examiner never invoked.
- [ ] **Step 3: Verify** — `mvn -B -Pdocker verify` → BUILD SUCCESS, both scenarios print their paths.
- [ ] **Step 4: Commit** — `test: approval-to-PR pipeline scenario`

---

### Task 10: Acceptance (requires the user — stop and ask before running)

- [ ] **Step 1:** Ask the user to stop any running `noir run`. Rebuild (`mvn -B -q package -DskipTests`), `bin/noir skill install`.
- [ ] **Step 2:** Server home: `mkdir -p ~/noir-server && printf 'repos:\n  - jwcarman/noir-test\n' > ~/noir-server/config.yaml`. The user starts, in their own terminal with their credential exported, `bin/noir --home ~/noir-server run`. Confirm `noir:draft` and `noir:sealing` labels now exist on noir-test.
- [ ] **Step 3:** The user starts `claude` in `~/IdeaProjects/noir-test` **without** exporting any credential, runs `/noir-spec`, writes a small spec, and approves publishing. Verify the issue has `noir:draft` and nothing happens on the server.
- [ ] **Step 4:** The user edits the issue title or body after adding `noir:ready` once (expect rejection comment, label removed, item DRAFT), then re-adds `noir:ready` (expect SEALING → … → PR_OPEN).
- [ ] **Step 5:** After the user merges or closes the PR: `bin/noir --home ~/noir-server status` shows approval time, seal time, seal rejections `edited_after_approval 1`, and the disposition. Ledger the result.
