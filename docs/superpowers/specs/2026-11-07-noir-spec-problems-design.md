# noir: spec problems and failure explanations (design)

Date: 2026-11-07
Status: approved in conversation (three sections), written spec pending review
Evidence: `docs/findings/2026-11-07-overnight-observations.md`, findings 2 and 3, Improvements 6,
9 and 10; noir-test #75 and #77 (both failed after 4 attempts on a locked test the spec did not list
under `## Tests to update`, with no explanation on the issue or PR); noir-test #72 (the Examiner
silently dropped an example that contradicted the Javadoc).

## Problem

1. **Agents notice that a spec is wrong, and noir discards it.** The builder's final message
   (`AgentResult.resultText`) reaches only the operator's logs. The Examiner's reasoning stays in
   `examiner/attempt-N/agent.log`. When the spec contradicts a locked test, or contradicts itself,
   the builder either quits silently (`NO_OUTPUT`, attempts 2–4 of #77) or picks one reading
   without saying so. The Examiner works around the contradiction, so the holdout suite never
   tests that case.
2. **Failures don't explain themselves.** The draft PR and the failure comment say only
   "noir could not satisfy every requirement within N attempts". The cause on #75 and #77 could be
   fixed in a minute: list `RomanNumeralsTest.java` under `## Tests to update`.

## Decisions

- **A reported spec problem stops the item and sends the issue back to draft.** No further
  attempts run. A human revises the spec and approves it again.
- **Both agents can report:** the builder during `BUILDING`, and the Examiner during `SEALING`
  (hidden tests on).
- **Reports are structured, and noir checks their references.** noir doesn't judge whether a
  claimed problem is real; a human does. It only rejects reports that cite nothing that exists.
- **The channel is a file the agent writes**, not a marker in its final message and not a new
  tool.

## 1. The report

### Location

- Builder: `/work/SPEC-PROBLEMS.json`. That is the attempt directory, outside `/work/repo`, so it is
  never committed and never reaches a PR branch.
- Examiner: `/work/out/spec-problems.json`, next to `holdout/` and `reference/`.

noir reads the file on the host after the agent's container exits. A symlink, a non-regular file
or a file over 64 KiB counts as no report.

### Format

```json
{
  "problems": [
    {
      "requirement": "R1",
      "files": ["src/test/java/org/jwcarman/noirtest/RomanNumeralsTest.java"],
      "explanation": "R1 requires toRoman(4000) to return MMMM, but the locked test RomanNumeralsTest.toRomanRejectsOutOfRange expects IllegalArgumentException for 4000. List RomanNumeralsTest under Tests to update, or drop the change."
    }
  ]
}
```

### Validation (`SpecProblems.parse(json, spec, baseFiles)`)

The report is **valid** when all of the following hold:

- It parses as JSON with a `problems` array of 1 to 10 entries. Unknown fields are ignored.
- Every entry has a non-blank `explanation`. Control characters are replaced with spaces, and the
  text is cut to 2,000 characters.
- Every entry names at least one reference, and every reference it names exists:
  - `requirement`: a requirement id in the spec (`R1`, …), or `intent` or `constraints`.
  - `files`: up to 10 paths, each a file at the base commit or one of the spec's stub paths.

A report that fails any check is **invalid**. noir records it in a `spec_problems_invalid` event
(with the reason) and ignores it, so the attempt carries on exactly as it would have without the
file. A broken report never stops an item.

### Prompt wording

The builder and Examiner prompts each gain a section, "When the spec cannot be satisfied". Report a
problem only when **no** implementation could meet the spec as written together with the files
and tests it locks: a requirement that contradicts a locked test, or two parts of the spec that
contradict each other. A requirement that is merely hard doesn't count. If any valid reading of the
spec works, build that reading. Write the file, then stop. Don't also try to work around the
problem.

The builder prompt's "Acceptance" section talks about hidden acceptance tests even when an item
has none. It is rewritten to depend on the item: with hidden tests off, it says the quality bar
and the locked tests are the gate.

## 2. Lifecycle

### Builder report

- `ContainerBuildRunner` reads `SPEC-PROBLEMS.json` after the agent exits, and `BuildOutcome` gains
  `Optional<SpecProblems> specProblems`.
- When the report is valid, `Factory` fires a new trigger, `SpecProblemReported`, instead of
  `AgentExited`. This happens whether or not the attempt made commits, and also when the agent ran
  out of time or budget.
- `Lifecycle`: `SpecProblemReported` is valid only from `BUILDING`, and moves the item to `DRAFT`
  with the effects `REJECT_APPROVAL` and `APPLY_LABEL`, as a rejected seal does. The transition
  carries no failure category.
- A `spec_problems` event records the problems (masked and capped), the attempt and the agent.
- The builder's commits are not delivered: no draft PR. The comment says where the operator can
  find them (`attempts/` under the item, moved to `runs/<n>/` on re-approval).
- Secret check order is unchanged. A credential in the attempt's patch is only found during
  measurement, which doesn't run here, so the report's text is masked with `Secrets.mask` before it
  is posted.

### Issue comment (both agents)

```
noir stopped work on this issue: the builder reports that the spec cannot be satisfied as
written. noir has not verified this; please check it.

- **R1** (`src/test/java/org/jwcarman/noirtest/RomanNumeralsTest.java`): R1 requires …

Revise the spec (for example, list a conflicting test under `## Tests to update`), then add
`noir:ready` again.
```

"the builder" becomes "the Examiner" for a seal rejection. Explanations are masked, stripped of
control characters, and shown in full up to the 2,000-character cap.

### Re-approval restarts the item

Today `Approved` moves a `DRAFT` item to `SEALING` and keeps its counters. An item that comes back
to `DRAFT` after building would then carry its attempt and rework counts over, and its next
attempt would start from the last attempt's bundle (`Factory.inputBundle`). So:

- When `Approved` fires on a `DRAFT` item whose `attempt > 0`, the item directory's `attempts/`
  moves to `runs/<n>/attempts/` (`n` = 1, 2, …), and the item resets to `attempt = 0`,
  `reworkCount = 0`.
- The reseal that follows pins a fresh base commit and a fresh baseline, as any seal does.
- A `restarted` event records `n` and the attempts it archived.

### Examiner report

- `Examiner` reads `out/spec-problems.json` after each attempt. A valid report ends examining at
  once: no further Examiner attempts and no seal checks.
- `SuiteSealer` returns it as a new examination outcome, and `ApprovalSealer` rejects the seal with
  a new `SealRejection.SPEC_PROBLEM` (event value `spec_problem`) and the comment above.
- An invalid report is ignored: the attempt's holdout and reference are checked as usual.

## 3. Explaining failures

When an item ends `FAILED`, the draft PR body and the failure comment
(`PullRequestBody.render(…, draft = true)` and `failureComment`) gain a `### Why it failed`
section, placed right after the shortfall line.

### One line per attempt

Each attempt's line is built from its recorded category and its feedback input. Runs of
consecutive attempts with the same text collapse into one line ("Attempts 2–4: …").

| Category | Line |
|---|---|
| `BUILD_FAILED` | "the build failed." |
| `CONTRACT_VIOLATION` | "contract violation: " + the first violation message, cut to 300 characters |
| `QUALITY_SHORTFALL` | "quality shortfall: " + each metric that fell short, with its value and the bar |
| `BEHAVIOR_FAILURE` | "hidden tests failed: " + per-requirement pass counts (what is published today) |
| `NO_OUTPUT` | "no commits; the builder stopped without changing anything." + its last message, quoted, at most 10 lines and 1,000 characters, labelled as the builder's words |

### Locked tests that failed

- `TestInventory.failedLocked(baseline, outcomes, changeable)` lists every test that passed at the
  baseline, failed or timed out in an attempt, and lives in a file the spec doesn't list under
  modify targets or Tests to update. The union across all attempts is published.
- This needs each attempt's test reports when the build failed. Measurement already copies them
  (the `test-reports` analyze step runs whatever the build's exit).
- Published as: "Existing tests that failed: `…`, `…`. This spec doesn't allow them to change. If
  the change is meant to alter what they check, list " + their source files + " under
  `## Tests to update`, then add `noir:ready` again." At most 20 tests are named, then "and N more".

### The builder hears it too

For an attempt whose build failed on locked tests, `FeedbackRenderer` adds the same list to the
builder's feedback, plus: "These failing tests are locked. If the spec cannot be met without
changing them, write `/work/SPEC-PROBLEMS.json` (see the prompt) instead of working around them."

### What is never published

- Holdout test names, sources, messages or traces. Behavior failures stay counts only.
- Build log tails. They stay in the builder's feedback, which is private.
- Every published line is masked with `Secrets.mask`, stripped of control characters, and cut to
  its cap.

## Out of scope

- Mechanically checking a reported problem, for example re-running a cited test. A human judges
  the substance.
- Reports from the builder during rework that only partly block the spec. A report always stops
  the item.
- Changing `noir status` or metrics beyond counting the new rejection reason
  (`ExperimentMetrics` already iterates over `SealRejection.values()`) and a `spec_problems` total.

## Testing

- `SpecProblemsTest`:
  - valid reports, with a requirement only, files only, and both;
  - each invalid case: bad JSON, 0 or more than 10 problems, a blank explanation, an unknown
    requirement, a missing file, no reference;
  - capping and stripping of control characters;
  - symlink and size limits on the file read.
- `LifecycleTest`: `SpecProblemReported` from `BUILDING` goes to `DRAFT` with the right effects
  and is illegal from every other state. `Approved` on a `DRAFT` item that has built resets its
  counters.
- `FactoryTest`:
  - a builder report stops the item, comments, and removes approval, with and without commits;
  - an invalid report changes nothing;
  - re-approval archives `attempts/` and the next attempt starts from the base bundle.
- `ApprovalSealerTest`: an Examiner report rejects with `spec_problem` and no seal checks. An
  invalid report is ignored.
- `PullRequestBodyTest`: exact text of "Why it failed" for each category, collapsing, the
  locked-tests hint, the caps, and that no holdout names appear.
- `PipelineIT`, in real containers:
  - a builder fixture that writes a report on attempt 1: the item goes to `DRAFT` with the comment;
  - a builder that fails on a locked test, then quits: the draft PR names the test and its file.
- Live acceptance: re-run the #77 spec on noir-test. Expected: the builder reports the
  `RomanNumeralsTest` conflict, noir comments, and the issue returns to draft. Then add the test
  under Tests to update, re-approve, and expect a PR.
