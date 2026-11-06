# noir: optional hidden tests (design)

Date: 2026-11-07
Status: approved in conversation
Evidence: `docs/findings/2026-11-07-hidden-test-efficacy.md` (hidden suites caught 1 of 594 mutants
the builder's own tests missed; grading never failed in 26 builds)

## Decision

Hidden tests are **off by default**. They are on for an item when the server sets
`hiddenTests: true` in `config.yaml`, or the issue carries the label `noir:hidden-tests` when it is
sealed. The measurement gate (default quality bar: coverage 90%, mutation 80%, max CRAP 15) is the
quality gate either way.

## With hidden tests off

- **Sealing** still parses and lints the issue, splices modify targets, checks stub and test paths
  against the base commit, enforces the approval rules, writes `spec.json`, `stubs/`, `fragments/`
  and `base.bundle`, and posts a commitment. It runs no repository inventory, no Examiner and no
  seal checks.
- **The commitment** keeps its format (version 2): `examinerModel` is `null` and the file list is
  empty, since there is no holdout or reference. Its comment on the issue says hidden tests are off.
- **Build and measurement** are unchanged, including the lock check.
- **Grading** does not run a container: the grade step records a `grade` event with
  `result: "skipped"` and the item goes to delivery (`GradePassed`).
- **The PR body** replaces `### Holdout results` with `### Hidden tests` and the line
  `Off for this item (enable with the noir:hidden-tests label or hiddenTests: true).`
- **Cost** is the builder only.

## With hidden tests on

Exactly as today.

## Details

- `noir:hidden-tests` joins the labels noir creates in every configured repository.
- The decision is read once, from the issue's labels when sealing starts, and recorded by the
  commitment (empty file list = off). Adding or removing the label after sealing has no effect.
- `noir audit` on an item without hidden tests reports that there is no holdout to verify.
- `noir status` is unchanged; "Holdout defects" counts only items that had hidden tests.

## Testing

Unit: config default and override; label read at sealing; sealing without the Examiner (no
inventory, no examine calls, commitment with null model and no files, comment text); the grade step
skipped from the commitment; PR body section; audit message. Pipeline IT: one item end to end with
hidden tests off (`SEALING > SEALED > BUILDING > MEASURING > GRADING > PR_OPEN`, no Examiner, no
grader container); existing scenarios run with hidden tests on.

Acceptance: one spec on noir-test with the default (off) and one with `noir:hidden-tests`.
