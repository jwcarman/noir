# Plan 5 (approval flow) — rulings, deferred minors and acceptance

Collected from the SDD ledger at completion (2026-11-05). Each ruling: what was decided — why — cost if wrong.

## Rulings

- Pre: Ruling: ApprovalSealer constructor also takes NoirConfig (after SuiteSealer) for examiner().attempts() in suiteTooWeak — plan ctor omitted it — cost if wrong: none
- Pre: Ruling: Task 6 updates every Factory construction site incl. PipelineIT/SealedItemFixture so test-compile stays green — cost if wrong: none
- Pre: Ruling: "existing outbound secret check" for ApprovalSealer comments = the same check Factory/GitHubDelivery applies to outbound text (OutboundSecret path); if none reachable from seal/, use Secrets with the configured secret set and refuse to post on a hit — cost if wrong: one helper move
- Task 1: Ruling: reviewer Minor 1 (null issue body → NPE) carried into Task 2 — GhCli.issue maps a null body to "" so every consumer gets a string — cost if wrong: none
- Task 2: Ruling: reviewer Minor "-F coerces all-digit owner/name" re-graded by effect (noir unusable for such repos) — fixed by controller: issueArgvUsesGraphqlWithVariables RED→GREEN, suite 619/619, commit 235b7c1 — cost if wrong: none
- Task 5: Ruling: comments are passed through Secrets.redact before posting (the Factory guard is private to Factory) — cost if wrong: a redacted marker instead of a refusal
- Task 5: Ruling: empty approval at seal time → EDITED_AFTER_APPROVAL with the same comment — label removed/re-edited means re-review — cost if wrong: misleading reason in one rare case
- Task 5: Ruling: accept Secrets.mask (secret removal preserving formatting; redact = mask + one-line/500-char trim) over redact for comments — redact would mangle the exact multi-line texts — cost if wrong: none
- Task 5: Ruling: rejection comments are posted by Task 6's rejectApproval, not inside seal() — matches plan (REJECT_APPROVAL side effect) — cost if wrong: none
- Task 5: Ruling: accept implementer's phrase mapping for unlisted problem types (skipped/symlink/UTF-8 → structural rules; nondeterministic → reference failed; no mutants → mutation score; failed-at-step → did not complete) — cost if wrong: slightly less precise feedback
- Task 5: Ruling: duplicate `approved` event on resealing after a pre-commitment crash is acceptable — metrics use the first approved event — cost if wrong: none
- Task 5: Ruling: re-graded by effect into fix round 1 — Minor 2 (non-atomic commitment.json write can wedge an item permanently), Minor 1 (RepoMirror path escape; cheap), Minor 3 (test compares a hand-built Rejected with itself) — cost if wrong: a small extra diff
- Task 6: Ruling: ExaminerFailedException (and any RuntimeException from ApprovalSealer other than the mapped infra types) → InfraBlocked in Factory; its message goes only to local events/listener (redacted), never to an issue comment or label — carries Task 5 hazard — cost if wrong: a model failure blocks instead of rejecting (operator resumes)
- Task 6: Ruling: re-graded by effect into fix round 1 — Minor 1 duplicate public rejection comments (split retries), Minor 2 PR_OPEN "open a new issue" is wrong advice (no warning for PR_OPEN), Minor 3 null-collaborator seam lets BLOCKED tests pass for the wrong reason (ApprovalSealing port), Minor 4 unreadable item → duplicate item violates one-item-per-issue (fail closed) — cost if wrong: a slightly larger diff
- Task 7: Ruling: re-graded Minor 1 (misaligned "Approval time (median)" line in user-visible status) — controller widens the label column after the implementer's background Docker run exits — cost if wrong: none
- Task 7: Ruling: NoirSpecSkillTest draft/seal filter is a bridge; Task 8 must restore real `noir spec` assertions (carried into T8 dispatch)
- Task 8: Ruling: retract the "$tmp variable" decision — use a recorded absolute <tmpdir> substituted literally — cost if wrong: none
- Task 8: Ruling: re-graded into fix round — revising while noir:ready is present must be refused (server could seal unapproved text; HITL integrity); load + diff original body; precondition also requires noir:draft; README mkdir -p and "server must watch the repo first" — cost if wrong: none
- Final: Ruling: re-graded by effect into the single fix wave — M2 seal-time label, M3 internal field names in public lint comments, M4 per-line trailing whitespace rejected, M5 no comment when BLOCKED during sealing (+README resume/cancel), M6 README design link, M7 end-to-end re-approval test — cost if wrong: a larger fix diff
- Final: Ruling: declined-to-judge items stand — withdrawing noir:ready mid-seal does not stop it (freeze at commitment); same-second edit counts as not after; 100-item timeline truncation; per-pass GraphQL cost; approver≠author out of scope — cost if wrong: revisit after acceptance
- Final: Ruling: brief's "second approved event" in M7 was a controller error (an edited-after-approval rejection records no approved event by design) — test asserts the single approved event carries the revised body sha — cost if wrong: none

## Deferred minors

- Task 1: minor (deferred): convoluted empty-Constraints check (IssueSpec.java:607-617); intent/statement line starting "## " is read as a section (rejected with an error, not silently wrong); round-trip tests lack multi-paragraph statement / multiple constraints / "## " inside a stub; StubLint cases possibly duplicated in DraftCheckTest (DraftCheck deleted in T7); FQN StubSource/SpecRenderer in Specs/SealerTest; spec↔render package cycle (plan-mandated); TODO lint also rejects a legitimate "TODO" in stub comments
- Task 2: minor (deferred): repo without "/" → StringIndexOutOfBounds outside try (config validates owner/name); labels/timeline truncated at 100; exit 128 also for bad repo dir in exists; untested null body / closed issue / lastEditedAt-only edit; raw gh output echoed unredacted in parse error; gh --remove-label on absent label unverified against real gh (check in T10)
- Task 3: minor (deferred): untested decided paths (both/neither file+--issue, GitHub error redaction, unreadable file, --issue parse failure, no subcommand); unreadable-file message not via Secrets.describe; ISSUE_REF field placement
- Task 4: minor (deferred): interim SEALED items labelled noir:building alongside noir:ready until T6 (cosmetic); Factory must handle SEALING step in T5/T6 or Daemon's active-item rule drives it into driveFailed→BLOCKED (carried to T6 dispatch)
- Task 5: minor (deferred): `approved` event may repeat on a non-recovery re-run (first one wins in metrics)
- Task 5: minor (deferred): AtomicFiles doesn't fsync parent dir; commitment.json now 0600; null repo → NPE in RepoMirror
- Task 6: minor (deferred): Retry around whole seal re-runs the paid examiner after a seal-check DockerException (consistent with build phase) — flag for final review; discovery reads all workitem.json per ready issue per pass (O(ready × items))
- Task 6: minor (deferred): after a removeReady exhaustion → BLOCKED, resume re-seals and may post a second rejection comment; no test for exhausted removeReady; no CANCELLED-warning test seen in fix diff
- Task 7: minor (deferred): 3-arg Operations test constructor; negative approval duration unguarded
- Task 8: minor (deferred): SKILL.md:31 long line; "read the default branch" lacks a mechanism; skillNeverAddsReadyLabel greps two literals only
- Task 8: minor (deferred): SKILL.md long lines; README comment placement above mkdir; "copy it to" not a literal cp
- Task 9: minor (deferred): examiner fixture rename depends on Listener.created firing before the Examiner (AtomicReference); hard-coded fixtureId; possibly unused imports in SealEndToEndIT
- Final: minor (deferred): M1 Retry around whole seal re-runs paid Examiner after a transient seal-check Docker failure; M8 suite-too-weak comments could name requirement ids/PIT score (plan decision)
- Final: minor (deferred): render/parse round trip collapses stubs ending in 2+ newlines to one (harmless); blank secret in set would suppress notices (same pattern as Factory.guard); guard refusal of block notice emits no event; BlockedNotice not retried on later passes if GitHub is down at block time; FQN Instant in Reconciler

## Acceptance (Task 10)

Run on `jwcarman/noir-test` with the server as `noir --home ~/noir-server run`
and `/noir-spec` in a desktop session with no credential.

- First pass: the server created `noir:draft` and `noir:sealing` and updated
  the `noir:ready` description.
- `/noir-spec` published issue #5 ("Add a Slugifier that turns a title into a
  URL slug", R1–R9) labelled `noir:draft`; the server created no item for it.
- `noir:ready` added, then the title edited 3 s later: one fixed-text comment,
  `noir:ready` removed (real `gh issue edit --remove-label` verified),
  `noir:draft` back, item `nr-01M6Q5FGYXKX22VG7DTDTES255` SEALING → DRAFT with
  `seal_rejected edited_after_approval`. No Examiner run, nothing spent.
- Title restored, `noir:ready` re-added: the same item went DRAFT → SEALING →
  SEALED (Examiner attempt 1, $0.38, 2m 4s) → BUILDING → MEASURING → GRADING →
  PR_OPEN in 3m 16s. PR #6: holdout 69/69 across R1–R9, coverage and mutation
  score 100%, max CRAP 4. Merged unmodified (disposition `accepted`).
- `noir status`: approval time 3m 3s, seal time 2m 4s, seal rejections
  `edited_after_approval 1`, cost $0.58 for the item.

## After acceptance

- The builder's commit was always `noir: attempt work`. Fixed in 5a5a7c4:
  `<issue title> (#<n>)`, `, attempt <k>` for rework (`CommitMessage`,
  `COMMIT_MESSAGE` env to `build-post.sh`).
- Idea for Plan 6: comment commands on issues (e.g. `/noir build`), found by
  the existing poll, restricted to users with write access. `@noir` is avoided
  because it is a real GitHub username.
