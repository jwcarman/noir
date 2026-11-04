# noir v1 — Design

**Date:** 2026-11-03
**Status:** Draft — revised after external review, pending author approval

## 1. Purpose

noir is a "dark factory" for software: once a unit of work passes a gate, it is
built, verified, and delivered with no human in the loop. It is simultaneously:

- **An experiment** in how far software autonomy can be pushed, measured
  honestly.
- **A tool** that does real work on the author's own Java projects.

The long-term goal is self-service: users describe what they want, and the
factory specifies, builds, and delivers it. v1 targets the author as the only
user, but the agent structure is chosen so that broadening the audience is an
interface change, not a redesign.

### Guiding shape

- **Upstream is interactive; downstream is dark.** Specification is a
  conversation. Once a work item is sealed at the gate, nothing downstream asks
  a human anything.
- **Layered autonomy.** Every switch that grants more autonomy (auto-merge,
  additional intake channels, non-technical users) is added only once
  measurements justify it (§10).
- **Trust is measured, not assumed.** Three layers: quality metrics
  (coverage, mutation score) measured by noir, independent re-verification in
  clean containers, and a sealed holdout acceptance suite the builder never
  sees.

## 2. Scope

### In scope for v1

- Interactive spec builder as a Claude Code skill (`/noir-spec`).
- Structured spec storage; GitHub issue and builder `SPEC.md` both rendered
  from it.
- Examiner agent that writes holdout tests and a sealed reference
  implementation from the visible spec only.
- Sealing with mechanical validation (satisfiability, discrimination,
  determinism) and a public hash commitment.
- Local daemon that builds work items in Docker containers using headless
  Claude Code.
- Clean-container measurement of coverage (JaCoCo) and mutation score (PIT),
  scoped to changed classes.
- Deterministic, network-isolated grader that runs the holdout suite.
- Pull request (or draft PR on failure) as the endpoint.
- Event log, `noir status`, optional `noir audit`, and an experiment protocol.

### Explicitly out of scope for v1 (deferred)

- Auto-merge (endpoint stays at "PR opened"; human merges).
- Intake channels other than the spec builder.
- Critic agent (automated fairness audit of holdout tests).
- Adjudicator agent (ruling on whether a failing holdout test or the code is
  wrong).
- Plain-language example confirmation for non-technical users.
- A different model provider for the Examiner (the port allows it; v1 uses
  Claude for all agents and records model IDs).
- Gradle, multi-module Maven, Java versions other than 21, non-Java targets.
- Egress allowlist / API-key proxy for builder containers.
- Concurrent work items; always-on host.
- Revealing holdout tests after PR closure.
- Store retention/garbage collection.

### Build assumptions (fail fast if violated)

- Target repo is a **single-module Maven project** with `pom.xml` at the root.
- **Java 21.**
- noir uses the **image's pinned Maven**; any repo `mvnw` is ignored.
- `noir seal` verifies these against the base commit and refuses to seal
  otherwise.

## 3. Architecture overview

```
 you ──▶ /noir-spec (Specifier skill, interactive)
              │  draft: spec.json + API stubs
              ▼
         noir seal
              │  pin base SHA; bundle repo
              │  Examiner (headless) ─▶ holdout tests + reference impl
              │  seal checks (container): stubs fail, reference passes,
              │                           holdout kills reference mutants,
              │                           deterministic across reruns
              │  create issue (rendered); post commitment; label noir:ready
              ▼
   ┌──────── noir run (daemon) ──────────────────────────────────────┐
   │ BUILDING   builder container: claude -p on SPEC.md (+FEEDBACK) │
   │               └─▶ git bundle of branch                          │
   │ MEASURING  build container (clean, from bundle):                │
   │               mvn verify, API conformance, JaCoCo + PIT         │
   │               (changed classes) ─▶ classes + dependency jars    │
   │ GRADING    grader container (no network, no Maven):             │
   │               holdout tests vs. artifacts, JUnit console        │
   │                                                                 │
   │   pass ─▶ push branch, open PR                                  │
   │   fail, retries left ─▶ REWORK (categorized coarse feedback)    │
   │   retries exhausted ─▶ draft PR (or issue comment), noir:failed │
   └─────────────────────────────────────────────────────────────────┘
```

GitHub holds the public record (issue = rendered spec, PR = result). noir's
private local store holds the source of truth and everything sealed.

## 4. The work-item contract (the gate)

A work item has two halves, created together and sealed together.

### 4.1 Visible half: structured spec

The source of truth is `spec.json` in the store, plus API stub source files.
Schema (v1):

```json
{
  "schemaVersion": 1,
  "id": "nr-<ulid>",
  "repo": "owner/name",
  "baseSha": "<commit>",
  "title": "...",
  "intent": "...",
  "requirements": [
    {
      "id": "R1",
      "statement": "...",
      "examples": [ { "given": "...", "when": "...", "then": "..." } ]
    }
  ],
  "constraints": ["..."],
  "qualityBar": { "lineCoverage": 0.80, "mutationScore": 0.70 },
  "apiStubs": ["src/main/java/.../Foo.java"]
}
```

From it noir renders:

- **The GitHub issue body** (Intent, API contract, Requirements with examples,
  Constraints, Quality bar), with a footer stating the issue is a rendering and
  edits do not affect the build.
- **`SPEC.md`** given to the builder.
- **The Examiner's input.**

Because all three are rendered from the sealed `spec.json`, editing the issue
after sealing cannot cause spec drift.

The API contract (stubs with signatures, types, Javadoc) is mandatory: holdout
tests must compile against code the Examiner has never seen. The spec pins the
surface; behavior is the builder's job.

### 4.2 Sealed half: noir store

```
~/.noir/store/<id>/
  spec.json         # visible spec (source of truth)
  stubs/            # API stub sources
  base.bundle       # git bundle of repo at baseSha
  holdout/          # JUnit 5 tests, package noir.holdout.<id>
  reference/        # Examiner's reference implementation (never shown to builder)
  commitment.json   # hashed manifest (§4.4)
  workitem.json     # state, retry count, attempt metadata
  events.jsonl      # append-only event log (§9.3)
  attempts/<n>/     # builder bundle, measurements, grader results per attempt
```

### 4.3 Holdout test rules

- **Fairness rule:** holdout tests may only test requirements stated in the
  visible spec. They are hidden *examples* of stated behavior, not hidden
  requirements. "Inferred edge cases" are limited to consequences of the stated
  requirements and standard Java semantics (e.g. `null` handling only if a
  requirement or the stub Javadoc addresses it).
- **Traceability:** every test method carries `@Tag("Rn")` (method level, one
  or more). Each test's Javadoc cites the requirement statement or example it
  derives from.
- **Dependencies:** JUnit Jupiter + JDK + the API under test only. Enforced
  mechanically by an import allowlist (`java.*`, `org.junit.jupiter.*`, the
  stubs' packages). No AssertJ, Mockito, or repo test dependencies — the
  Examiner cannot see them.
- **Determinism:** no network, no filesystem outside a JUnit `@TempDir`, no
  sleeps, no wall-clock reads, no unseeded randomness, no thread creation.
  Stated in the Examiner prompt; checked by the import allowlist and the seal
  rerun (§5.3).
- **Timeouts:** each test runs with a 10 s timeout; the whole suite with a
  10 min timeout. A timeout is a failure.
- **Namespace:** package `noir.holdout.<id>` to avoid colliding with builder
  sources. A builder that creates sources in `noir.holdout` is a contract
  violation.
- **Aggregation:** results are reported per requirement tag; a test with
  multiple tags counts toward each tag. Totals count distinct tests.

### 4.4 Integrity commitment

At sealing, noir writes `commitment.json`:

```json
{
  "commitmentVersion": 1,
  "baseSha": "<commit>",
  "specSha256": "<hash of canonical spec.json + stubs>",
  "examinerModel": "<model id>",
  "files": [ { "path": "holdout/...", "sha256": "..." },
             { "path": "reference/...", "sha256": "..." } ]
}
```

Canonicalization: paths are relative, `/`-separated, sorted by UTF-8 bytes;
file contents are UTF-8 with LF line endings; no modes or symlinks (symlinks
are rejected). JSON is serialized with sorted keys and no insignificant
whitespace. The SHA-256 of `commitment.json` is posted as an issue comment.
This proves neither the spec nor the holdout suite changed after sealing.

### 4.5 Lifecycle

Persisted states: `SEALED`, `BUILDING`, `MEASURING`, `GRADING`, `REWORK`,
`PR_OPEN`, `FAILED`, `BLOCKED`, `CANCELLED`. (Drafts live under
`~/.noir/store/drafts/` and are not work items.)

| From | Trigger | Guard | Side effects | To |
|---|---|---|---|---|
| SEALED | daemon pickup | issue open, labeled `noir:ready` | label `noir:building`; attempt 1 | BUILDING |
| REWORK | daemon pickup | — | attempt n+1 with FEEDBACK.md | BUILDING |
| BUILDING | agent exits (any reason incl. budget) | — | commit leftover changes; extract bundle | MEASURING |
| MEASURING | build/verify fails, API nonconformant, or quality bar missed | retries left | write categorized feedback | REWORK |
| MEASURING | all checks pass | — | export artifacts | GRADING |
| GRADING | all holdout tests pass | — | push branch, open PR, label `noir:pr-open` | PR_OPEN |
| GRADING | behavioral failures | retries left | write per-requirement feedback | REWORK |
| MEASURING / GRADING | failure | retries exhausted | push branch + draft PR if any commits, else issue comment; label `noir:failed` | FAILED |
| GRADING | holdout fails to compile despite API conformance | — | record holdout defect; label `noir:blocked` | BLOCKED |
| any active | infrastructure failure persists past backoff | — | label `noir:blocked` | BLOCKED |
| SEALED / REWORK / active | issue closed, or `noir cancel <id>` | — | stop containers; label removed | CANCELLED |
| BLOCKED | `noir resume <id>` | blocked by infrastructure or secret scan (not holdout defect) | restart the phase that blocked | (that phase) |

A holdout-defect block cannot be resumed (the sealed suite is immutable); the
author cancels the item and re-specifies it.

Every transition appends an event. Default retry budget: **3 rework cycles**
(4 attempts total).

## 5. Spec builder

### 5.1 Specifier: `/noir-spec` skill

A Claude Code skill run inside a local clone of the target repo. It is the only
interactive component.

1. The user describes the work. The skill reads the repo for context (existing
   APIs, conventions, test style).
2. Together they produce API stubs and numbered requirements with concrete
   examples. The skill pushes back on vague requirements ("handles errors
   gracefully") in favor of testable ones ("throws `IllegalArgumentException`
   when key is blank").
3. The skill writes `spec.json` and stubs to `~/.noir/store/drafts/<id>/` —
   **never into the repo working tree** — and on user approval invokes
   `noir seal <id>`.

**Mechanical spec lint** (run by `noir seal`): schema valid; requirement IDs
unique and sequential; every requirement has ≥ 1 example; every stub file
compiles. Subjective-language checks remain a prompt instruction.

### 5.2 Examiner

The Examiner is a headless `claude -p` agent behind its own `AgentPort`
(swappable to another provider later). Its only input is the rendered visible
spec plus stubs; it has no access to the Specifier conversation, the repo's
implementation, or any builder output. It produces:

- **Holdout tests** following §4.3, several per requirement.
- **A reference implementation** of the stubs, used only during sealing to
  prove the tests are satisfiable and discriminating. It is sealed and never
  shown to the builder.

`noir seal` makes **up to 3 Examiner attempts**, one invocation each. On a
failed seal check, the next attempt receives the check report. Each attempt is
recorded as an event. After 3 failures sealing aborts with a report to the
user.

### 5.3 Sealing checks

Run in a container from the bundle at `baseSha`. `noir seal` refuses to seal
unless all of:

1. **Build assumptions** (§2) hold at `baseSha`.
2. **Spec lint** passes (§5.1).
3. **Coverage of requirements:** every requirement has ≥ 1 holdout test; every
   tag names an existing requirement.
4. **Import allowlist** passes for all holdout tests.
5. **Compiles** against the stubs.
6. **Fails against stubs:** every holdout test fails against the stubs (bodies
   throw `UnsupportedOperationException`).
7. **Satisfiable:** every holdout test passes against the reference
   implementation, on **3 consecutive runs** (determinism check).
8. **Discriminating:** PIT run on the reference implementation using the
   holdout tests reaches a mutation score ≥ **0.70** (configurable). This
   rejects assertion-free or weak tests that would pass any non-throwing
   implementation.

On success: write `commitment.json`; create the issue from the rendering; post
the commitment hash; apply `noir:ready`; move the draft to
`~/.noir/store/<id>/` in state `SEALED`.

Checks 7–8 prove the tests are *consistent and meaningful*, not that they are
*correct*: the reference and tests come from the same Examiner. Correctness
is measured through PR disposition and audits (§10).

## 6. Builder

### 6.1 Pickup

`noir run` polls configured repos for open issues labeled `noir:ready` with a
matching `SEALED` item, and for items in `REWORK`. It transitions to
`BUILDING` and relabels the issue `noir:building`.

### 6.2 Sandbox

A fresh container from the **builder image** (JDK 21, Maven, git, Claude Code),
pinned by digest. The repo is cloned **from `base.bundle`** (attempt 1) or
from the previous attempt's bundle (rework) onto branch
`noir/<issue#>-<slug>`. No GitHub access is needed, so private repos work.

Injected:

- `SPEC.md` — rendered from `spec.json`.
- `FEEDBACK.md` — on rework only (§8.2).
- An Anthropic API key.

Not available: the noir store, GitHub credentials, holdout tests, the
reference implementation.

Hardening: runs as non-root; no Docker socket; CPU, memory, and PID limits;
outbound network open (Maven Central, Anthropic API) — restriction deferred.

**Prompt injection:** repo content (README, comments, issues) may contain
instructions aimed at the agent. The builder prompt states that only
`SPEC.md` and `FEEDBACK.md` are instructions. The API key is the only secret
present; noir scans the produced bundle for it before pushing anything and
fails the item to `BLOCKED` if found.

### 6.3 Agent

Headless `claude -p --output-format json` with a builder system prompt:
implement the stubs exactly as specified, write your own tests, iterate with
`mvn verify` until the quality bar is met.

Default budgets per attempt (configurable): **200 turns, 60 min wall clock,
$10 cost**. Cost is read from the CLI's JSON output. Whichever is hit first
ends the attempt; it then proceeds to measurement like any other exit. Budget
exhaustion is not a separate outcome — the attempt is judged on what it
produced.

### 6.4 Handoff

When the agent exits, noir commits any uncommitted changes as
`noir: uncommitted work at agent exit`, extracts the branch as a git bundle to
`attempts/<n>/`, and discards the container. If the branch has no commits
beyond base, the attempt is recorded as `NO_OUTPUT`.

## 7. Measurement

A fresh **build container** (same image as the builder, no agent, no API key)
cloned from the attempt's bundle. Nothing from the builder's container
survives, so lingering processes and fabricated reports are excluded.

In order, stopping at the first failure:

1. **Build:** `mvn verify` with the image's Maven. Failure → `BUILD_FAILED`.
2. **API conformance:** noir compares the compiled public API (via `javap`)
   against the stubs' public signatures. Missing or changed members, or any
   source in `noir.holdout` → `CONTRACT_VIOLATION`.
3. **Quality**, scoped to **changed classes** (main-source classes added or
   modified relative to `baseSha`):
   - Line coverage from JaCoCo, invoked by plugin coordinates on the command
     line (no POM changes needed in the target repo).
   - Mutation score from PIT with `targetClasses` set to the changed classes.
   - Below the bar → `QUALITY_SHORTFALL`.
4. **Export artifacts:** compiled main classes and the runtime dependency jars
   (`dependency:copy-dependencies`, runtime scope).

**Limit:** the builder controls the POM, so a determined builder could
influence measurement (e.g. coverage excludes). The quality bar is therefore a
builder-facing guide and a reported metric; the **holdout suite is the
acceptance gate.**

## 8. Grader and delivery

### 8.1 Grader

Deterministic; no LLM. A fresh **grader container** from the grader image
(JDK 21, noir's pinned JUnit Platform Console Launcher; **no Maven, no
network**), pinned by digest. Inputs: the exported main classes, dependency
jars, and holdout test sources. No builder build logic runs here.

1. noir compiles the holdout tests against main classes + dependency jars +
   its own JUnit, with noir's JUnit jars first on the classpath.
   Compile failure → `HOLDOUT_DEFECT` (API conformance already passed, so the
   tests themselves are at fault) → `BLOCKED`.
2. Run with the JUnit Platform Console Launcher. Classpath = noir's JUnit +
   main classes + dependency jars + compiled holdout tests. Builder test
   classes and resources are absent; JUnit extension auto-detection is
   disabled; timeouts per §4.3.
3. **Flake check:** failing tests are re-run twice. Inconsistent outcomes are
   recorded as `FLAKY` and count as failures (the holdout tests were proven
   deterministic against the reference, so flakiness is attributed to the
   builder's code).

### 8.2 Feedback

Full per-test results stay in `attempts/<n>/`. The builder's `FEEDBACK.md`
contains only categorized, coarse information:

| Category | Feedback content |
|---|---|
| `BUILD_FAILED` | Maven output tail (the builder's own build, no secrets involved) |
| `CONTRACT_VIOLATION` | List of missing/changed public signatures vs. the visible stubs |
| `QUALITY_SHORTFALL` | Measured coverage and mutation score vs. the bar, per changed class |
| `BEHAVIOR_FAILURE` | Per-requirement counts, e.g. `R3: 3/5 passed` |
| `NO_OUTPUT` | "The previous attempt produced no commits." |

Never holdout test names, source, assertion messages, or stack traces.

### 8.3 Outcomes

noir (on the host, the only holder of GitHub credentials) acts:

- **All holdout tests pass** → push branch, open PR linked to the issue. Label
  `noir:pr-open`.
- **Failure, retries remain** → `REWORK`.
- **Retries exhausted** → if the branch has commits, push and open a **draft**
  PR; otherwise post an issue comment. Label `noir:failed`.

PR body includes: base SHA, attempt count, coverage and mutation score,
per-requirement holdout results, commitment hash reference, model IDs, and
image digests. Holdout tests and the reference implementation are never
pushed.

## 9. Core and operations

### 9.1 Stack

- Java 21, Maven, picocli CLI: `noir seal | run | status | audit | cancel |
  resume`. `noir status --json` for scripting.
- No application framework in v1.
- Ports isolate external effects:
  - `ContainerPort` — shells out to `docker`.
  - `GitHubPort` — shells out to `gh`.
  - `AgentPort` — shells out to `claude -p`; separate instances for builder
    and Examiner; replaceable by scripted fakes.

### 9.2 Configuration

`~/.noir/config.yaml`: repos; default quality bar (line coverage 0.80,
mutation score 0.70); seal discrimination threshold (0.70); per-attempt budgets
(200 turns, 60 min, $10); rework retries (3); poll interval (60 s); container
resource limits; image digests.

### 9.3 State, events, and concurrency

- `workitem.json` is the source of truth for state. Writes are atomic (temp
  file + rename).
- `events.jsonl` is append-only. Each event: `{ "v": 1, "ts", "itemId",
  "type", "attempt", "data" }`. Types include state transitions, agent runs
  (model, turns, cost, duration), measurements, grades, Examiner attempts, PR
  disposition, and audit verdicts.
- **Single-instance lock** at `~/.noir/noir.lock`; a second `noir run` exits.
- **One work item at a time.**
- **Startup reconciliation:** `workitem.json` wins over GitHub. On start, noir
  reapplies the label matching each item's state, cancels items whose issue
  was closed, and restarts `BUILDING`/`MEASURING`/`GRADING` items at the start
  of that phase from their last stored bundle.

### 9.4 Failure handling

- **Infrastructure errors** (Docker, network, GitHub, API outage): retried with
  exponential backoff (up to 5 tries); do **not** consume the retry budget.
  Persistent failure → `BLOCKED`.
- **Agent outcomes** (`BUILD_FAILED`, `CONTRACT_VIOLATION`,
  `QUALITY_SHORTFALL`, `BEHAVIOR_FAILURE`, `NO_OUTPUT`): consume retry budget.
- **Holdout defects:** `BLOCKED`; recorded as experiment data.

### 9.5 Testing noir

- **Unit tests** against fake ports: state machine transitions (every row of
  §4.5), feedback rendering (asserting no holdout detail leaks), spec lint,
  import allowlist, commitment canonicalization, config.
- **Pipeline integration tests** with a tiny fixture Java repo, real Docker,
  and a scripted fake agent:
  - correct solution → `PR_OPEN`;
  - wrong solution → rework → `FAILED`;
  - API signature changed → `CONTRACT_VIOLATION` feedback;
  - **malicious builder**: POM plugin that leaves a background process,
    `.mvn/extensions.xml`, a JUnit extension that swallows failures, sources
    in `noir.holdout`, faked report files → grader still reports failures;
  - seal check: assertion-free Examiner tests are rejected by the
    discrimination check.
- **Acceptance:** one real end-to-end run on one of the author's repos, from
  `/noir-spec` through sealed item, build, measurement, grade, and PR.

## 10. Experiment protocol

**Hypothesis:** given a sealed spec, noir delivers PRs that the author accepts
without modification, at a rate high enough to justify auto-merge.

**Primary metric:** *holdout first-pass rate* — fraction of items whose first
attempt passes grading.

**Secondary metrics:**

- Rework cycles per item; outcome distribution by failure category.
- Cost and wall time per item.
- Human minutes per item (duration of the `/noir-spec` session).
- **PR disposition**, derived automatically from GitHub: *accepted* (merged
  with no additional commits), *modified* (merged after additional commits),
  *rejected* (closed unmerged). This is the ground truth on whether passing the
  holdout suite meant the work was right.
- **Holdout defects:** `HOLDOUT_DEFECT` events plus disagreements recorded via
  `noir audit`.

**Calibration:** `noir audit <id>` shows the holdout tests per requirement and
records agree/disagree (with optional note) per test. It is optional and never
blocks the pipeline.

**Auto-merge rule (provisional):** after **≥ 20 completed items**, consider
enabling auto-merge when **≥ 90 % of PRs were accepted unchanged** and **no
confirmed holdout defect** occurred in those items. `noir status` reports
progress against this rule.

All metrics are computed from `events.jsonl`; model IDs and image digests are
recorded per event so results can be compared across model or image changes.

## 11. Alternatives considered

- **CI runner (e.g. GitHub Actions) instead of a local daemon:** no
  infrastructure to manage, but holdout tests would have to be present in a
  runner the repo's own workflows can influence, and per-repo secrets would
  multiply. A local daemon keeps the sealed store under noir's sole control.
  The container design makes a later move to an always-on host trivial.
- **Issue body as source of truth:** simpler at first, but every edit to the
  issue would be spec drift, and parsing markdown back into structure is
  fragile. Rendering from `spec.json` avoids both.
- **LLM grader:** more flexible acceptance criteria, but nondeterministic and
  itself in need of validation. Executable tests give a reproducible verdict.
- **Gradle / multi-module support now:** widens the surface of build-tool
  behavior the grader must neutralize before the core loop is proven.
- **Different provider for the Examiner now:** reduces correlated blind spots
  but adds a second integration before the pipeline exists. The `AgentPort`
  split keeps it a configuration change later.

## 12. Future directions

In rough order of expected value:

1. Auto-merge switch, gated by the §10 rule.
2. Different-provider Examiner as an experiment arm.
3. Critic and Adjudicator agents.
4. Additional intake channels (dependency/CVE signals, CI failures, runtime
   monitoring, user-filed issues).
5. Plain-language example confirmation → non-technical self-service users.
6. Network egress restriction, concurrency, always-on host.
7. noir building noir.
