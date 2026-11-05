# noir approval flow — design

Date: 2026-11-05
Status: draft for review
Builds on: `2026-11-03-noir-v1-design.md` (v1), Plan 4 (specifier and metrics)

## 1. Problem

In v1 the human's only approval happens in the `/noir-spec` chat, and sealing
(the expensive Examiner run) happens in that same desktop session, before
anyone has approved the published issue. That has four costs:

1. There is no human-in-the-loop step on the issue itself; `noir:ready` is
   applied by noir at seal time and re-applied by reconciliation.
2. Seal cost (Examiner + PIT, 5–15 min, real money) is paid before approval, and
   again for every revision.
3. The desktop session needs the Anthropic credential, which its own agent can
   read.
4. The spec lives in a local draft store (`~/.noir/store/drafts`), so the
   specifier and the factory must share a machine.

The goal is a **noir server** that watches repositories and reacts, fully
decoupled from the desktop. GitHub is the only thing the two share.

## 2. Workflow

1. **Write (desktop).** `/noir-spec` co-writes the spec with the user and
   publishes it as a GitHub issue labelled `noir:draft`. No seal, no credential,
   no local store.
2. **Approve (GitHub).** A human reviews the issue and adds `noir:ready`.
3. **Build (server).** `noir run` locks the issue body, seals it (Examiner
   writes and noir checks the holdout suite), posts the commitment, then builds,
   measures, grades and delivers — v1's pipeline from SEALED onward, unchanged.
4. **Review (GitHub).** The human reviews the PR; dispositions are recorded as
   in Plan 4.

The holdout suite is kept and its guarantees are unchanged; only *when* and
*where* it is written moves. It exists only on the server.

## 3. Lifecycle

```
            (human adds noir:ready)
DRAFT* ───────────────────────────> SEALING ──> SEALED ──> BUILDING → … → PR_OPEN
  ^                                    │
  └── rejected: edited after approval, │
      parse/lint errors, suite too weak (noir comments, removes noir:ready)
```

| State | Label | Meaning |
|---|---|---|
| DRAFT | `noir:draft` | Awaiting (re-)approval. Server-side, only items rejected at least once are DRAFT. |
| SEALING (new) | `noir:sealing` | Lock check, parse, lint, clone, examine, suite checks, commitment. |
| SEALED | `noir:building` | Transient checkpoint so a crash resumes with the build. |
| BUILDING … CANCELLED | unchanged | |

\* Before first approval an issue has no server-side work item; `noir:draft` is
applied by the skill and the server ignores such issues.

Rules:

- **`noir:ready` is the human's.** noir never adds it. noir removes it only when
  rejecting, always with a comment giving the reason. After a successful seal it
  stays, as a record of approval. It is removed from the set of labels
  `setNoirLabel` strips; `ensureLabels` still creates it.
- **One work item per issue.** The server creates the item (generating its
  `nr-` id) at first approval. Re-approval after a rejection moves the same item
  back to SEALING; seal attempts and timings accumulate on it.
- **Closing the issue** cancels the item from any non-terminal state (as v1).
- **The body is frozen once SEALED.** Later edits never affect the build; noir
  comments once that they are ignored. To change the spec, close the issue and
  open a new one.

## 4. The issue body is the spec

The issue body is the single source of the spec. There is no hidden machine
copy. The format is the v1 rendering, made strict:

````
## Intent

<one or more paragraphs>

## API contract

### <repo-relative path ending .java>

```java            (fence: 3+ backticks, longer than any run inside)
<stub source>
```

## Requirements

### R1

<statement paragraph(s)>

- **Given** <…> **when** <…> **then** <…>

## Constraints

- <constraint>          (or the single line `_None._`)

## Quality bar

- Line coverage ≥ <n>%
- Mutation score ≥ <n>%
- Max CRAP score per method ≤ <n>

---

_noir spec · add `noir:ready` to approve · edits after sealing are ignored_
````

- The **title** is the issue title; the body does not repeat it.
- Sections appear in exactly this order; each is required. Requirement ids are
  `R1`, `R2`, … consecutive from 1. Each requirement has at least one example.
- The trailing footer (a line `---` followed only by an italic `_noir …_` line)
  is optional and ignored.
- Quality-bar values are read as written; lint requires percentages in 0–100 and
  CRAP > 0.
- `repo` comes from the issue's repository; `baseSha` is pinned at seal time
  (§6); `id` is assigned by the server.
- Parsing is strict. Errors carry the body line number and a fix hint. The
  parser and v1's renderer round-trip: `parse(render(spec)) == spec`.
- `SPEC.md` for the builder is still rendered from the parsed `Spec` (with the
  `# <title>` heading and injection guard), so the builder's view is identical to
  v1.

## 5. Desktop

The noir jar on the desktop is used only for checking; nothing is stored.

| Command | Purpose |
|---|---|
| `noir spec check <file>` | Parse + lint a body file with the server's code. |
| `noir spec check --issue <owner/repo>#<n>` | Same, for a live issue. |
| `noir spec template` | Print an empty body in the format. |
| `noir skill install` | Unchanged. |

`/noir-spec`:

1. Preconditions: inside a git clone, `gh` authenticated, and the repo has the
   `noir:*` labels (created by a server watching it). Missing labels → stop and
   say no noir server watches this repo.
2. Read the repo; co-write the spec one question at a time (as Plan 4), drafting
   in a temp directory outside the working tree.
3. `noir spec check` until clean; show the user the exact title and body.
4. On approval of the text: `gh issue create --label noir:draft`.
5. Revision: `/noir-spec #N` loads the issue body, revises with the user, checks,
   and `gh issue edit`s it. Refuses if the issue is past DRAFT.
6. **Never adds `noir:ready`** and never reads the server store; it ends by
   telling the user to review the issue and add the label.

Removed: `noir seal`, `noir draft init|list|check|show`, the desktop
`~/.noir/store/drafts`, and `spec.json` as a hand-edited file (the server still
writes it into the item directory as the frozen parsed copy).

Limitation (accepted for now): the skill uses the user's `gh` token, so noir
cannot distinguish a label added by the skill from one added by the user. A
future config option may require the approver to differ from the issue author.

## 6. Server: sealing inside `noir run`

**Pickup.** Each pass, per configured repo: list open issues labelled
`noir:ready`. Each without an active item gets one (state SEALING), or its DRAFT
item moves to SEALING. Items are still driven one at a time, oldest first.

**Lock check.** Via GraphQL, read the time and actor of the most recent
`noir:ready` LabeledEvent and the issue's `lastEditedAt` (title or body). If
`lastEditedAt` is later than the label time → reject "edited after approval;
re-review and re-add `noir:ready`". Otherwise record an `approved` event:
approver login, `approvedAt`, `bodySha256`, title.

**Parse and lint** (§4). Errors → reject, listing them.

**Repository.** The server keeps a clone per repo under
`<home>/repos/<owner>/<repo>`, fetched before each seal. `baseSha` = head of the
default branch. A stub whose path already exists at `baseSha` → reject (stubs
only add files).

**Examine.** v1's seal logic unchanged: up to `examiner.attempts` (3) attempts;
the suite must fail on stubs, pass on the reference 3×, PIT ≥ 0.70. Examiner
attempts record `examiner_attempt` events with `costUsd`.

**Suite too weak** → reject with a comment that names only requirement ids and
numbers (e.g. "PIT score 0.62 (need 0.70)", "R3: no test failed on the stubs").
It never contains test names or test source; full detail goes to the event log
only.

**Rejection** (any cause): comment with the reason, remove `noir:ready`, set the
item to DRAFT (`noir:draft`), and record `seal_rejected` with `reason`
(`edited_after_approval` | `invalid_spec` | `suite_too_weak` | `stub_exists`).

**Infrastructure failures** (Docker, network, GitHub) follow v1 infra handling:
retry up to `infraRetries`, then BLOCKED.

**Commitment** covers the holdout suite, `bodySha256` and `baseSha`. Post it as
an issue comment, transition SEALED, then pick up for BUILDING in the same pass.

**Crash recovery.** In SEALING: if `commitment.json` exists, finish posting and
continue; otherwise restart the seal from the lock check (correct, possibly
costly).

**Edits after sealing.** During reconciliation, if `lastEditedAt` > the
`sealed` event time and no notice has been posted, comment once that edits do
not affect this build.

Credentials and Docker are required only on the server.

## 7. Metrics

| Metric | Definition |
|---|---|
| Approval time (median) | issue `createdAt` → first `approvedAt`; replaces "Human time" and is labelled as including waiting time |
| Seal rejections | count per reason across items |
| Seal time (median) | first SEALING → SEALED |
| Cost | builder + Examiner (unchanged sources) |
| Wall time, first-pass rate, PR disposition, holdout defects, auto-merge | unchanged |

`draft_created` / `seal_requested` are no longer written; old items keep them
and the human-time computation for them is dropped.

## 8. Migration

- Existing items (both PR_OPEN) are unaffected; all new event types are
  additive.
- `ensureLabels` adds `noir:draft` and `noir:sealing`; `noir:ready`'s description
  becomes "Approved by a human; noir will seal and build".
- Skill and README are rewritten for the desktop/server split.
- The server runs as `noir run` in a terminal or tmux. Packaging as a service
  (systemd, container) is out of scope.

## 9. Testing

- Parser: round-trip over all existing fixtures; each malformation (missing or
  out-of-order section, bad requirement id, example without when/then, unclosed
  fence, bad quality bar) fails with the right line.
- Daemon against `FakeGitHub` (extended with label timeline and `lastEditedAt`):
  approval → SEALING → SEALED → BUILDING; edited-after-approval rejection;
  invalid spec rejection; suite-too-weak comment contains no test names;
  re-approval reuses the item; close in DRAFT cancels; crash in SEALING with and
  without `commitment.json`; post-seal edit gets exactly one comment; noir never
  adds `noir:ready` and reconciliation never strips it.
- `PipelineIT`: an approval-to-PR scenario.
- Acceptance: the user writes a spec with `/noir-spec` and adds `noir:ready`; the
  server runs as `noir run --home <separate dir>`, sharing nothing with the
  desktop but GitHub.

## 10. Out of scope

Issue body as anything but the §4 format; approver ≠ author enforcement;
parallel Examiner/builder; multi-module or non-Maven targets; server packaging.
