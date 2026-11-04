# noir v1 — Design

**Date:** 2026-11-03
**Status:** Approved design, pending implementation plan

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
  measurements justify it.
- **Trust is measured, not assumed.** Three layers: in-loop quality metrics
  (coverage, mutation score), independent re-verification, and a sealed holdout
  acceptance suite the builder never sees.

## 2. Scope

### In scope for v1

- Interactive spec builder as a Claude Code skill (`/noir-spec`).
- Examiner agent that writes holdout tests from the visible spec only.
- Sealing with mechanical validation and a public hash commitment.
- Local daemon that builds work items in Docker containers using headless
  Claude Code.
- noir-run measurement of coverage (JaCoCo) and mutation score (PIT).
- Deterministic, tamper-resistant grader that runs the holdout suite.
- Pull request (or draft PR on failure) as the endpoint.
- Event log supporting experiment metrics; `noir status`.

### Explicitly out of scope for v1 (deferred)

- Auto-merge (endpoint stays at "PR opened"; human merges).
- Intake channels other than the spec builder (runtime monitors, dependency
  signals, user-filed bugs).
- Critic agent (automated fairness audit of holdout tests).
- Adjudicator agent (ruling on whether a failing holdout test or the code is
  wrong) and the holdout-defect-rate metric it produces.
- Plain-language example confirmation for non-technical users.
- Gradle; non-Java targets.
- Network restriction for builder containers.
- Concurrent work items.
- Revealing holdout tests after PR closure.

## 3. Architecture overview

```
 you ──▶ /noir-spec (Specifier skill, interactive)
              │  draft: visible spec + API stubs
              ▼
         noir seal ──▶ Examiner (headless agent, sees visible spec only)
              │            │ writes holdout tests
              │◀───────────┘
              │  mechanical checks; create issue; post hash; label noir:ready
              ▼
   ┌──────── noir run (daemon) ─────────────────────────────────────┐
   │  poll issues ─▶ BUILDING: builder container (claude -p)        │
   │                    │ git bundle                                │
   │                    ▼                                           │
   │                 noir measures coverage + mutation (in-container)│
   │                    │                                           │
   │                    ▼                                           │
   │                 GRADING: grader container (no LLM, holdout)    │
   │                    │                                           │
   │        pass ──▶ push branch, open PR                           │
   │        fail ──▶ REWORK (coarse feedback) ─▶ BUILDING           │
   │        budget exhausted ──▶ draft PR, noir:failed              │
   └────────────────────────────────────────────────────────────────┘
```

GitHub holds the public record (issue = visible spec, PR = result). noir's
private local store holds everything sealed.

## 4. The work-item contract (the gate)

A work item has two halves, created together and sealed together.

### 4.1 Visible half: GitHub issue

Created in the target repo, labeled `noir:ready`. The body has a fixed markdown
structure:

| Section | Content |
|---|---|
| **Intent** | A few sentences on why this work matters. |
| **API contract** | Java source stubs (signatures, types, Javadoc) pinning the public surface. The builder must implement exactly these. |
| **Requirements** | Numbered behavioral statements `R1`, `R2`, …, with concrete examples. This is the complete definition of correct. |
| **Constraints** | E.g. "no new dependencies", "Java 21". |
| **Quality bar** | Minimum line coverage and mutation score; defaults from config. |

The API contract is mandatory because holdout tests must compile against code
the Examiner has never seen. The spec pins the surface; behavior is the
builder's job.

### 4.2 Sealed half: noir store

```
~/.noir/store/<id>/
  workitem.json     # id, repo, issue number, state, retry count, hash
  events.jsonl      # append-only event log
  holdout/          # JUnit 5 tests, package noir.holdout
  results/          # full per-test grader results (never shown to builder)
```

Each holdout test is tagged with the requirement it probes: `@Tag("R3")`.

### 4.3 Fairness rule

Holdout tests may only test requirements stated in the visible spec. They are
**hidden examples of stated behavior, not hidden requirements**: the test set is
drawn from the same distribution as the spec, the builder just never sees the
samples.

### 4.4 Integrity commitment

At sealing, noir computes a SHA-256 over the holdout bundle (deterministic:
sorted relative paths and file contents) and posts it as a comment on the issue.
This proves the holdout suite was not altered after the build.

### 4.5 Lifecycle

```
SEALED ─▶ BUILDING ─▶ GRADING ─▶ PR_OPEN
           ▲    │        │
           │    └────────┴─▶ REWORK  (quality failure from BUILDING, or
           └──────────────── REWORK   holdout failure from GRADING;
                                      retries remain)

BUILDING | GRADING ─▶ FAILED   (retry budget exhausted; draft PR)

any active state ─▶ BLOCKED  (persistent infrastructure failure)
```

Issue labels mirror state: `noir:ready`, `noir:building`, `noir:pr-open`,
`noir:failed`, `noir:blocked`.

Default retry budget: 3 rework cycles.

## 5. Spec builder

### 5.1 Specifier: `/noir-spec` skill

A Claude Code skill run inside a local clone of the target repo. It is the only
interactive component.

1. The user describes the work. The skill reads the repo for context (existing
   APIs, conventions, test style).
2. Together they produce API stubs and numbered requirements with concrete
   examples. The skill rejects vague requirements ("handles errors gracefully")
   in favor of testable ones ("throws `IllegalArgumentException` when key is
   blank").
3. The skill writes the draft to `~/.noir/store/drafts/<id>/` — **never into
   the repo working tree** — and on user approval invokes `noir seal <id>`.

### 5.2 Examiner

`noir seal` runs the Examiner as a single headless `claude -p` invocation in a
container. Its only input is the visible spec (rendered issue body + API
stubs). It has no access to the Specifier conversation, the repo's
implementation, or any builder output. It writes several JUnit 5 tests per
requirement — from the stated examples and from inferred edge cases — tagged
by requirement.

### 5.3 Sealing checks

`noir seal` refuses to seal unless all of:

1. Every requirement has ≥ 1 holdout test; every test's tag names an existing
   requirement.
2. The holdout suite **compiles** against the API stubs (in a container).
3. The holdout suite **fails** against the stubs, whose bodies throw
   `UnsupportedOperationException`. Any test passing against the stubs is
   rejected as vacuous.

On success: create the issue, post the hash comment, apply `noir:ready`, move
the draft to `~/.noir/store/<id>/` in state `SEALED`.

On check failure, the Examiner is re-run with the check output (up to a small
fixed limit), then sealing fails with a report to the user.

The user may inspect holdout files in v1, but the flow never requires it.

## 6. Builder runner

### 6.1 Pickup

`noir run` polls configured repos for issues labeled `noir:ready` with a
matching `SEALED` item in the store. It transitions the item to `BUILDING` and
relabels the issue `noir:building`.

### 6.2 Sandbox

A fresh container from the **builder image** (JDK 21, Maven, git, Claude Code).
The repo is cloned inside the container onto branch `noir/<issue#>-<slug>`.

Injected into the container:

- `SPEC.md` — the visible spec.
- `FEEDBACK.md` — on rework only.
- An Anthropic API key.

Not available to the container: the noir store, GitHub credentials, any
holdout material. The builder cannot push, comment, or read anything sealed.

### 6.3 Agent

Headless `claude -p` with a builder system prompt: implement the stubs exactly
as specified, write your own tests, iterate with `mvn verify` until the quality
bar is met. Bounded by turn, wall-clock, and cost budgets from config.

### 6.4 Measurement

After the agent exits, noir runs, in the same container:

- `mvn verify`
- JaCoCo and PIT invoked from the command line (no POM changes required in
  target repos).

noir parses the reports itself; the agent's self-reported numbers are ignored.
Below the quality bar → `REWORK` with the measured shortfall in `FEEDBACK.md`
(consumes retry budget).

### 6.5 Handoff

noir extracts the branch's new commits as a git bundle, stores it, and discards
the container.

### 6.6 Rework

A new container is started from the existing branch (restored from the
bundle), with `FEEDBACK.md` containing either the quality shortfall or the
coarse holdout signal.

## 7. Grader and delivery

### 7.1 Grader

Deterministic; no LLM. A fresh container from the **grader image** (JDK 21,
Maven, JUnit Platform Console Launcher):

1. Clone base repo; apply the builder's bundle.
2. `mvn compile` and `mvn dependency:build-classpath` to obtain main classes
   and runtime dependency classpath.
3. Copy holdout tests in (package `noir.holdout`); noir compiles them against
   that classpath with its own pinned JUnit 5 version.
4. Run with the JUnit Platform Console Launcher. Classpath = main classes +
   runtime dependencies + holdout tests + noir's JUnit. The builder's test
   classes and test resources are excluded; JUnit extension auto-detection is
   disabled.

This bypasses any builder-controlled surefire configuration or test-time
extensions.

### 7.2 Results and feedback

Per-test outcomes are aggregated by `@Tag` and stored in full in
`results/`. The builder receives only per-requirement counts, e.g.:

```
R1: 4/4 passed
R3: 3/5 passed
```

Never test names, source, assertion messages, or stack traces.

### 7.3 Outcomes

noir (on the host, the only holder of the GitHub token) acts:

- **All holdout tests pass and quality bar met** → push branch, open PR linked
  to the issue. PR body: coverage, mutation score, per-requirement holdout
  results, reference to the hash commitment. Label `noir:pr-open`.
- **Failure, retries remain** → `REWORK`.
- **Retry budget exhausted** → push branch, open **draft** PR with
  per-requirement results, label `noir:failed`.

Holdout tests are never pushed.

## 8. Core, operations, and testing

### 8.1 Stack

- Java 21, Maven, picocli CLI: `noir seal`, `noir run`, `noir status`.
- No application framework in v1.
- Three ports isolate external effects:
  - `ContainerPort` — shells out to `docker`.
  - `GitHubPort` — shells out to `gh`.
  - `AgentPort` — shells out to `claude -p`; replaceable by a scripted fake.

### 8.2 Configuration

`~/.noir/config.yaml`: repos, default quality bar (line coverage, mutation
score), budgets (agent turns, wall-clock, cost, rework retries), poll interval.

### 8.3 State and audit

`workitem.json` holds current state; `events.jsonl` is an append-only record of
every transition, measurement, grade, and cost. This log is the experiment's
raw data: holdout first-pass rate, retries per item, cost per item, quality
trends. `noir status` summarizes it.

### 8.4 Concurrency

One work item at a time.

### 8.5 Failure handling

- **Infrastructure errors** (Docker, network, GitHub, API outages): retried with
  backoff; do **not** consume the work item's retry budget. Persistent failure
  → `BLOCKED`, label `noir:blocked`, human attention required.
- **Agent failures** (quality bar or holdout not met): consume retry budget.
- **Daemon restart:** items in `BUILDING` or `GRADING` restart that phase from
  their last stored bundle. Containers are disposable, so restart is safe.

### 8.6 Testing noir

- **Unit tests** against fake ports for state machine, feedback rendering,
  sealing checks, config, hashing.
- **Pipeline integration test** with a tiny fixture Java repo, real Docker, and
  a scripted fake agent:
  - fake agent commits a correct solution → PR path;
  - fake agent commits a wrong solution → rework then failed path;
  - **malicious builder** case: fake agent disables or subverts tests via POM
    and a JUnit extension → grader must still report failures.
- **Acceptance:** one real end-to-end run on one of the author's actual repos,
  from `/noir-spec` through sealed item, build, grade, and PR.

## 9. Future directions

In rough order of expected value:

1. Auto-merge switch, gated on measured holdout first-pass rate.
2. Critic and Adjudicator agents; holdout defect rate metric.
3. Additional intake channels (dependency/CVE signals, CI failures, runtime
   monitoring, user-filed issues).
4. Plain-language example confirmation → non-technical self-service users.
5. Network restriction, concurrency, move to an always-on host.
6. noir building noir.
