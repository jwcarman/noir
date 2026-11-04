# noir Plan 4 — Specifier, Audit, and Experiment Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close out the v1 spec: the interactive `/noir-spec` skill, `noir audit`, PR-disposition tracking, and the §10 experiment metrics in `noir status` — then run the spec's acceptance (one real end-to-end item from `/noir-spec` to PR).

**Architecture:** Everything is computed from `events.jsonl` (spec §10). New events: `draft_created`, `seal_requested` (human minutes), `pr_disposition` (ground truth), `audit_verdict` (calibration), plus a `category` field on `transition` events. A pure `metrics` package folds per-item event histories into an `ExperimentReport`; `noir status` renders it. The Specifier is a Claude Code skill shipped inside the jar and installed by `noir skill install`; it drives the existing `noir draft` / `noir seal` CLI and never writes into the repo working tree.

**Tech Stack:** Java 21, picocli 4.7.7, Jackson 2.22.4, JavaParser 3.28.2, JUnit 6.1.3, AssertJ 3.27.7, `gh` CLI.

**Spec:** `docs/superpowers/specs/2026-11-03-noir-v1-design.md` (§5.1 Specifier, §9.1 CLI, §9.3 events, §10 experiment protocol).

## Global Constraints

- Package root `org.jwcarman.noir`; Java 21; every dependency/plugin version is a pom property.
- Formatting: Spotless google-java-format GOOGLE (`mvn spotless:apply` before commit; `validate` checks it).
- Event shape stays `{ "v": 1, "ts", "itemId", "type", "attempt", "data" }`; new events are additive, old logs must still parse.
- Metrics are computed from `events.jsonl` only (plus `workitem.json` for current state); `noir status` makes no network calls unless `--refresh` is given.
- `noir audit` is optional and never blocks or changes the pipeline (no state transitions).
- Holdout tests and reference code are shown only by `noir audit` to the human; nothing in this plan sends them to GitHub or to any agent.
- The Specifier writes only under `~/.noir/store/drafts/<id>/` — never into the target repo working tree.
- Auto-merge rule (§10): **≥ 20 completed items**, **≥ 90 %** of PRs accepted unchanged, **no confirmed holdout defect**.
- Commit trailers: `Co-Authored-By: <implementing model> <noreply@anthropic.com>`.

## Review Focus

1. **Old event logs** (the real Counter item has no `draft_created`, no `category`, no `pr_disposition`): metrics must treat missing data as "unknown/excluded", never crash or count as zero. → Task 5 test `itemsWithoutNewEventsAreCountedButExcludedFromDerivedMetrics`.
2. **PR merged after extra human commits, squash-merged, or closed unmerged**: disposition must be modified / accepted / rejected respectively, recorded once. → Task 2 tests.
3. **Unreadable or truncated item** (bad `workitem.json`, interrupted event tail): `noir status` still prints the other items and the metrics. → Task 5 test `unreadableItemIsSkippedByMetrics`.
4. **Audit of a test name that does not exist / item not found / repeated verdicts**: clear one-line error, exit 1; latest verdict per test wins. → Task 4 tests.
5. **Skill drift**: the skill text names CLI commands/options that must exist. → Task 6 test `everyNoirCommandInTheSkillParses`.

---

### Task 1: Event enrichment and GitHub PR state

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/factory/Transitions.java` (persist)
- Modify: `src/main/java/org/jwcarman/noir/factory/Bundles.java`, `GitBundles.java`, `src/main/java/org/jwcarman/noir/deliver/BundleRepo.java`
- Modify: `src/main/java/org/jwcarman/noir/github/GitHubPort.java`, `GhCli.java`
- Create: `src/main/java/org/jwcarman/noir/github/PullRequestState.java`
- Test: `TransitionsTest`, `GitBundlesTest`, `GhCliTest`; update `FakeGitHub` and any test `Bundles` fakes

**Interfaces:**
- Produces: `transition` event data gains `"category": "<Category>"` whenever `to` is `REWORK` or `FAILED` and the trigger implies one: `MeasurementFailed(c)` → `c`, `GradeFailed` → `BEHAVIOR_FAILURE`, `AgentExited(false)` → `NO_OUTPUT`. Implement as `static Optional<Category> Transitions.categoryOf(Trigger)`.
- Produces: `String Bundles.tip(Path bundle, String branch)` — full SHA of `branch` in the bundle (`git rev-parse refs/heads/<branch>` in the fresh bare repo `BundleRepo` already makes).
- Produces: `record PullRequestState(int number, Status status, String headSha, Instant mergedAt)` with `enum Status { OPEN, MERGED, CLOSED }`; `PullRequestState GitHubPort.pullRequest(String repo, int number)`. GhCli: `gh pr view <n> --repo <repo> --json number,state,headRefOid,mergedAt` (state `OPEN|MERGED|CLOSED`; `mergedAt` null unless merged). Unexpected output → `GitHubException` (not `NumberFormatException`).
- FakeGitHub: `merge(int pr, String headSha)`, `closePullRequest(int pr)`; `pullRequest` reports `OPEN` with the head given at creation (`"fake-head"` default) until changed.

- [ ] **Step 1: Failing tests** — `TransitionsTest.reworkFromMeasurementRecordsItsCategory` (data `category` = `QUALITY_SHORTFALL`), `gradeFailureRecordsBehaviorFailure`, `agentExitWithoutCommitsRecordsNoOutput`, `nonFailureTransitionHasNoCategory`; `GitBundlesTest.tipIsTheBranchHeadInTheBundle`; `GhCliTest.pullRequestParsesMergedState`, `pullRequestParsesOpenStateWithNullMergedAt`, `pullRequestWithGarbageOutputIsAGitHubException` (asserts the exact argv above).
- [ ] **Step 2:** Run `mvn -B -q test -Dtest='TransitionsTest,GitBundlesTest,GhCliTest'` — Expected: FAIL (compile errors / missing data).
- [ ] **Step 3:** Implement as specified.
- [ ] **Step 4:** Same command — Expected: PASS. Then `mvn -B -q verify` — Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: transition categories, bundle tip, PR state lookup`.

### Task 2: PR disposition tracking

**Files:**
- Create: `src/main/java/org/jwcarman/noir/factory/Dispositions.java`
- Modify: `src/main/java/org/jwcarman/noir/factory/Daemon.java` (call after reconcile, each pass), `src/main/java/org/jwcarman/noir/cli/Wiring.java`, `StatusCommand.java` (`--refresh`)
- Test: `src/test/java/org/jwcarman/noir/factory/DispositionsTest.java`, `DaemonTest`, `StatusCommandTest`

**Interfaces:**
- Consumes: `GitHubPort.pullRequest`, `Bundles.tip`, `delivered` event data `pullRequest` / `draft` / `deliveredAttempt`.
- Produces: `public Dispositions(Store store, GitHubPort gh, Bundles bundles, Clock clock)`; `List<String> Dispositions.refresh(Consumer<String> warn)` returns ids that got a new disposition. For every item with a `delivered` event carrying `pullRequest` and no `pr_disposition` event yet: query the PR; if `OPEN` do nothing; else append `pr_disposition` with data `{ "pullRequest": n, "draft": bool, "disposition": "accepted"|"modified"|"rejected", "mergedAt": iso|null, "headSha": "<observed>", "deliveredSha": "<tip>" }`.
  - `accepted` = MERGED and `headSha` equals `Bundles.tip(attempts/<deliveredAttempt>/bundle, branch)`; `modified` = MERGED otherwise (including tip unavailable — bundle missing); `rejected` = CLOSED.
  - Branch name from `BranchNames` exactly as delivery used it.
  - Per-item failures are caught, reported through a `Consumer<String>` warning sink passed to `refresh`, and do not stop other items.
- `noir status --refresh` runs `Dispositions.refresh` before printing (needs `gh`); `Daemon.runOnce` runs it after reconciliation, warnings via `Listener.warning`.

- [ ] **Step 1: Failing tests** — `mergedAtDeliveredHeadIsAccepted`, `mergedWithExtraCommitsIsModified`, `closedUnmergedIsRejected`, `openPrRecordsNothing`, `dispositionIsRecordedOnce` (second refresh makes no gh call for that item), `missingBundleMergedIsModified`, `oneItemFailingDoesNotStopTheOthers`, `draftPrDispositionKeepsDraftFlag`; `DaemonTest.passRecordsDispositions`; `StatusCommandTest.refreshRecordsDispositionsBeforePrinting`.
- [ ] **Step 2:** `mvn -B -q test -Dtest='DispositionsTest,DaemonTest,StatusCommandTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same — Expected: PASS; `mvn -B -q verify` — BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: record PR disposition (accepted/modified/rejected)`.

### Task 3: Draft commands for the Specifier

**Files:**
- Modify: `src/main/java/org/jwcarman/noir/cli/DraftCommand.java`, `src/main/java/org/jwcarman/noir/seal/Sealer.java`
- Create: `src/main/java/org/jwcarman/noir/spec/DraftCheck.java`
- Test: `DraftCommandTest`, `DraftCheckTest`, `SealerTest`

**Interfaces:**
- `noir draft init` additionally appends `draft_created` (attempt 0, data `{ "repo", "title" }`) to the draft's `events.jsonl`, and prints `Created draft <id>` then the directory (unchanged text).
- `noir draft list` — one line per draft: `<id>  <repo>  <title>`; `No drafts.` when none.
- `noir draft check <id>` — prints each problem on its own line and exits 1, or prints `Draft <id> OK` and exits 0. `List<String> DraftCheck.check(Path draftDir)`:
  - `SpecLint.lint(spec)` with the `baseSha` problem ignored (seal fills `baseSha`);
  - every `apiStubs` path exists under `stubs/`, is a regular file, is not a symlink, parses with JavaParser, and its `package` matches its directory;
  - `stubs/` contains no `.java` file that `apiStubs` does not list;
  - every requirement/example field contains no literal `TODO`.
- `noir draft show <id>` — prints `SpecRenderer.issueBody(spec, stubs)` (exactly what the issue will say).
- `Sealer.seal` appends `seal_requested` (attempt 0, `{}`) to the draft's events on its **first** call only (skip if one exists).

- [ ] **Step 1: Failing tests** — `initRecordsDraftCreated`, `listShowsDrafts`, `listWithNoDrafts`, `checkPassesACompleteDraft`, `checkReportsTodoPlaceholders`, `checkIgnoresMissingBaseSha`, `checkReportsMissingStub`, `checkReportsUnlistedStub`, `checkReportsPackageMismatch`, `checkReportsUnparseableStub`, `checkRejectsSymlinkedStub`, `checkExitsOneOnProblems`, `showPrintsTheIssueBody`, `SealerTest.sealRecordsSealRequestedOnce`.
- [ ] **Step 2:** `mvn -B -q test -Dtest='DraftCommandTest,DraftCheckTest,SealerTest'` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same — PASS; `mvn -B -q verify` — BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: draft list/check/show and specifier timing events`.

### Task 4: `noir audit`

**Files:**
- Create: `src/main/java/org/jwcarman/noir/cli/AuditCommand.java`, `src/main/java/org/jwcarman/noir/audit/HoldoutCatalog.java`
- Modify: `NoirCli.java` (register)
- Test: `AuditCommandTest`, `HoldoutCatalogTest`

**Interfaces:**
- `HoldoutCatalog.load(Spec spec, Path holdoutDir)` → `List<HoldoutTest>`; `record HoldoutTest(String id, Set<String> requirements, String source)` where `id` is `SimpleClassName#method` and `source` is the method's source text (JavaParser, Javadoc included). Uses `HoldoutAnalyzer`/`TagIndex` for tags.
- `noir audit <id>` — for each requirement in spec order: `R<n>: <statement>`, then each test tagged with it: `  [<verdict or ->] <test id>` followed by its source indented 4 spaces. A test tagged with several requirements appears under each.
- `noir audit <id> --test <testId> (--agree | --disagree) [--note <text>]` — validates the test id exists, appends `audit_verdict` `{ "test", "verdict": "agree"|"disagree", "note", "requirements": [..] }` (attempt = item's current attempt), prints `Recorded <verdict> for <testId>`. Latest verdict per test wins when displayed.
- Errors (one line, exit 1): unknown item, unknown test (`No holdout test <testId> in <id>`), both or neither of `--agree/--disagree`.
- No state change, no GitHub call, no agent call.

- [ ] **Step 1: Failing tests** — `catalogListsEveryTestWithItsTagsAndSource`, `listGroupsTestsByRequirementInSpecOrder`, `multiTagTestAppearsUnderEachRequirement`, `verdictIsRecordedAsAnEvent`, `latestVerdictWinsInListing`, `unknownTestIsAnError`, `unknownItemIsAnError`, `agreeAndDisagreeTogetherIsAnError`, `auditNeverChangesWorkItem`.
- [ ] **Step 2:** `mvn -B -q test -Dtest='AuditCommandTest,HoldoutCatalogTest'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same — PASS; `mvn -B -q verify` — BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: noir audit — review holdout tests and record verdicts`.

### Task 5: Experiment metrics in `noir status`

**Files:**
- Create: `src/main/java/org/jwcarman/noir/metrics/ItemHistory.java`, `ExperimentReport.java`, `ExperimentMetrics.java`
- Modify: `StatusCommand.java`
- Test: `ExperimentMetricsTest`, `StatusCommandTest`

**Interfaces:**
- `record ItemHistory(WorkItem item, List<Event> events)`; `ExperimentReport ExperimentMetrics.compute(List<ItemHistory> items)`.
- `ExperimentReport` fields (null = no data):
  - `int items`, `Map<State,Integer> byState`;
  - `Double firstPassRate` — over items that have a `grade` or terminal outcome: fraction whose attempt-1 `grade` event has `result: passed`; items cancelled before any grade are excluded;
  - `Double meanReworkCycles` — over PR_OPEN/FAILED items, `WorkItem.reworkCount`;
  - `Map<Category,Integer> failureCategories` — count of `transition` events with a `category`;
  - `BigDecimal totalCostUsd`, `BigDecimal meanCostUsdPerItem` — sum of `costUsd` over `build_attempt` and `examiner_attempt` events; mean over items with any cost;
  - `Duration medianWallTime` — first `SEALED→BUILDING` transition to first transition into PR_OPEN/FAILED;
  - `Duration medianHumanTime` — `draft_created` to `seal_requested` (items with both);
  - `int accepted, modified, rejected` — from `pr_disposition` of **non-draft** PRs only; `int completed = accepted + modified + rejected`;
  - `int holdoutDefects` — `grade` events with `result: holdout_defect` plus tests whose latest `audit_verdict` is `disagree`;
  - `AutoMerge autoMerge` — `record AutoMerge(int completed, int required /*20*/, Double acceptedRate, double requiredRate /*0.90*/, int holdoutDefects, boolean eligible)`; `eligible` iff `completed ≥ 20 && acceptedRate ≥ 0.90 && holdoutDefects == 0`.
- `noir status` prints the existing table, a blank line, then an `Experiment` block, e.g.:
  ```
  Experiment (1 item)
    First-pass rate      100% (1/1)
    Rework cycles        0.0 per item
    Failure categories   none
    Cost                 $0.24 total, $0.24 per item
    Wall time (median)   1m 13s
    Human time (median)  n/a
    PR disposition       0 accepted, 0 modified, 0 rejected
    Holdout defects      0
    Auto-merge           not eligible: 0/20 completed items
  ```
  Values with no data print `n/a`. `--json` adds `"metrics": { ... }` beside `"items"` with the same fields (durations as ISO-8601, rates as numbers).
- Items whose `workitem.json` or events cannot be read are skipped by metrics (they already show as UNREADABLE in the table).

- [ ] **Step 1: Failing tests** — `emptyStoreHasNoData`, `firstPassRateCountsAttemptOneGrades`, `cancelledBeforeGradeIsExcludedFromFirstPass`, `reworkCyclesAverageTerminalItems`, `failureCategoriesCountTransitionCategories`, `costSumsBuilderAndExaminer`, `wallTimeIsMedianBuildToTerminal`, `humanTimeUsesDraftCreatedToSealRequested`, `dispositionCountsExcludeDraftPrs`, `holdoutDefectsCountGradeDefectsAndDisagreements`, `autoMergeEligibleAtTwentyAcceptedNoDefects`, `autoMergeNotEligibleBelowNinetyPercent`, `itemsWithoutNewEventsAreCountedButExcludedFromDerivedMetrics`, `unreadableItemIsSkippedByMetrics`; `StatusCommandTest.statusPrintsExperimentBlock`, `statusJsonIncludesMetrics`.
- [ ] **Step 2:** `mvn -B -q test -Dtest='ExperimentMetricsTest,StatusCommandTest'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same — PASS; `mvn -B -q verify` — BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: experiment metrics and auto-merge progress in noir status`.

### Task 6: The `/noir-spec` skill

**Files:**
- Create: `src/main/resources/skills/noir-spec/SKILL.md`
- Create: `src/main/java/org/jwcarman/noir/cli/SkillCommand.java` (`noir skill install [--dir <path>]`, default `~/.claude/skills`)
- Modify: `NoirCli.java`, `README.md` (Quick start uses `/noir-spec`)
- Test: `SkillCommandTest`, `NoirSpecSkillTest`

**Interfaces:**
- `noir skill install` copies the resource to `<dir>/noir-spec/SKILL.md` (creating dirs, overwriting), prints the path.
- SKILL.md: frontmatter `name: noir-spec` and a `description` saying when to use it (user wants noir to build something in the current repo). Body, in this order:
  1. Preconditions: run inside a clean local clone of the target repo; repo must be in `~/.noir/config.yaml`; single-module Maven, Java 21 (spec §2) — stop and say so otherwise.
  2. Read the repo for context (existing APIs, packages, conventions, test style).
  3. Conversation, one question at a time: intent → API surface → numbered requirements with concrete `given/when/then` examples. Push back on untestable language (e.g. "handles errors gracefully" → "throws `IllegalArgumentException` when key is blank"). Only stated requirements will be tested (fairness rule §4.3), so anything that matters must be a requirement or in stub Javadoc.
  4. `noir draft init --repo <owner/name> --title "<title>"`; then write `spec.json` (schema §4.1) and stub sources under the printed draft directory's `stubs/` — **never in the repo working tree**. Stub bodies throw `UnsupportedOperationException`; Javadoc states behavior.
  5. `noir draft check <id>` until it prints OK; then `noir draft show <id>` and ask the user to approve that exact text.
  6. On approval: `noir seal <id> --repo-dir .`, report the issue number and commitment; tell the user `noir run --once` builds it (with the credential exported in their own terminal).
- `NoirSpecSkillTest.everyNoirCommandInTheSkillParses`: extracts every inline/fenced line starting with `noir ` from SKILL.md, replaces `<...>` placeholders with sample values, and asserts picocli parses it against `NoirCli` without `UnmatchedArgumentException`/`MissingParameterException` (parse only, no execution). Also `frontmatterHasNameAndDescription`, `skillNeverTellsTheAgentToWriteIntoTheRepo` (no instruction to write under `src/` of the working tree).

- [ ] **Step 1: Failing tests** — `installCopiesSkill`, `installOverwritesExisting`, plus the three `NoirSpecSkillTest` tests.
- [ ] **Step 2:** `mvn -B -q test -Dtest='SkillCommandTest,NoirSpecSkillTest'` — FAIL.
- [ ] **Step 3:** Write SKILL.md and the command.
- [ ] **Step 4:** Same — PASS; `mvn -B -Pdocker verify` — BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat: /noir-spec skill and noir skill install`.

### Task 7: Acceptance (requires the user — stop and ask before running)

Spec §9.5: one real end-to-end run from `/noir-spec` through sealed item, build, measurement, grade, and PR, on one of the author's repos.

- [ ] **Step 1:** Ask the user which repo; confirm it is single-module Maven on Java 21, add it to `~/.noir/config.yaml`, rebuild the jar, `bin/noir skill install`.
- [ ] **Step 2:** The user runs `/noir-spec` in a Claude Code session inside that repo, approves, and seals (credentials exported in their own terminal).
- [ ] **Step 3:** The user runs `bin/noir run --once`. Expected: `PR_OPEN` (or a draft PR with feedback).
- [ ] **Step 4:** After the user merges or closes the PR (and noir-test#2), run `bin/noir status --refresh`. Expected: `pr_disposition` events, and the Experiment block shows the dispositions and human time for the new item.
- [ ] **Step 5:** Record the outcome in the ledger.
