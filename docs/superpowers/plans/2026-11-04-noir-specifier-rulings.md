# Plan 4 (specifier and metrics) — rulings and deferred minors

Collected from the SDD ledger at completion (2026-11-04). Each ruling: what was decided — why — cost if wrong.

## Rulings

- Pre: Ruling: Daemon gets a `Dispositions` constructor parameter (after reconciler) and calls refresh(listener::warning) after reconciliation each pass; failures inside refresh are per-item warnings and never stop the pass — plan names the behavior but not the seam — cost if wrong: one constructor reshuffle
- Pre: Ruling: every GitHubPort/Bundles test double (FakeGitHub, inline fakes in tests) gains the new methods in Task 1 — cost if wrong: none
- Task 1: Ruling: implementer concern — Factory writes its own transition events with key "failure" (Category name) and never calls Transitions.persist; the plan's metric field is "category" — Factory's transition data must also carry "category" (same value; keep "failure" so older logs and readers stay valid); Task 5 reads "category" and falls back to "failure" for old logs — enters Task 1 fix loop — cost if wrong: one redundant field
- Task 5: Ruling: zero items → table mode prints only "No work items." (no Experiment block); JSON includes metrics with items 0; display formats (durations "1m 13s", money 2dp HALF_UP, "100% (1/1)", auto-merge reason lines) fixed by controller since the plan shows one example only — cost if wrong: cosmetic rework
- Task 5: Ruling: metrics read `category` with fallback to `failure` for older logs (carries Task 1 ruling) — cost if wrong: none
- Task 6: Ruling: reviewer Minors "HEAD not checked to be default branch" and "mktemp path discarded" re-graded Important — they are incorrect instructions in the user-facing skill (agent would specify against code the seal never sees / cannot follow the cleanup step) — included in fix round 1 with the "10s by default" wording — cost if wrong: a few lines of prose
- Final: Ruling: re-graded by effect into the single fix wave — Minor 5 (null headRefOid → permanent wrong `modified`; ground-truth corruption), Minor 8 (skill must forbid the agent running `noir audit`; holdout isolation), Minor 2 redaction part (unredacted warnings; secret hygiene), plus cheap user-facing Minors 1/3/4/6/7/9 and ledger minor default-branch detection — all in ONE fix dispatch — cost if wrong: a slightly larger fix diff
- Final: Ruling: declined-to-judge items stand as plan decisions — completed = non-draft PR dispositions only; holdout defects counted across all items (conservative); human time ends at first seal request (plan "first call only"); DraftCheck parses but does not compile (compile happens at seal); automated holdout_defect counts as confirmed (spec lists HOLDOUT_DEFECT events as defects); findPullRequest/delivery and examiner costUsd predate branch; agent reading ~/.noir/store directly from host is outside v1 (skill text forbids audit; filesystem isolation of the specifier is future work) — cost if wrong: metric definitions revisited after acceptance data
- Final: Ruling: residual (Important, new in fix wave) — ExperimentReport.percent floors a double (`new BigDecimal(rate*100)`), so exact rates like 57/100 display 56% and 29/100 display 28%; fix = compute from integer counts (passed*100/total) — no second fix wave per process → surfaced to user with a recommendation to apply the ~3-line fix before merge — cost if wrong: all percentages in noir status can read 1 point low

## Deferred minors

- Task 1: minor (deferred): GhCli.pullRequest GitHubException drops cause; number field intValue() 0 on non-numeric; unknown state untested; TransitionsTest lacks AgentExited(false)→FAILED and non-REWORK/FAILED-with-failure-trigger cases; FakeGitHub.merge mergedAt fixed EPOCH
- Task 1: minor (deferred): Factory category from transition.failure() vs Transitions.categoryOf from trigger — two sources (Lifecycle fail(NO_OUTPUT) for AgentExited(false) agrees today); FactoryTest helper uses FQN java.util.Map
- Task 2: minor (deferred): status-without-refresh test asserts factory not invoked, not gh untouched; FQN WorkItem in Dispositions; no test that latest delivered wins; first disposition is final (plan-mandated; PR_OPEN/FAILED are terminal so a later PR for the same item cannot occur in v1); TDD RED only compile-level for initial tests
- Task 2: minor (deferred): permanently corrupt spec.json/bundle warns on every refresh forever (no cap)
- Task 3: minor (deferred): DraftCheck filters lint by copied message string (share a constant/code); untested: symlinked stubs/ parent, malformed apiStubs no-disk, TODO in when/then, unreadable spec.json in list, check with invalid id/missing stubs dir; FQN style nits, double new Store in Init; list skips dirs without spec.json; TDD RED only for Sealer part
- Task 4: minor (deferred): holdout files parsed twice (analyzer + catalog); test ids use top-level simple class name — duplicates across packages/nested classes resolve to first; \r-only line endings in source slicing; untested: neither-flag message, --note/--agree without --test, requirement with no tests, missing holdout dir; FQN style nits
- Task 4: minor (deferred): UnreadableHoldoutException message printed without Secrets.describe (inconsistent with generic path)
- Task 5: minor (deferred): even-count median untested; whole-percent rounding can print "90% accepted (need 90%)" while not eligible (89.5%); %.1f and %n locale/platform dependent; compute() ~120 lines with repeated type scans; statusPrintsExperimentBlock runs status twice; ExperimentMetricsTest RED was stub errors not assertion failures
- Task 6: minor (deferred): write-into-repo test is a heuristic (copy verbs, any stubs/ line passes); parse test does not require a leaf command; SkillCommandTest mutates user.home (serial surefire only); skill hard-codes ~/.noir/config.yaml (ignores --home); skill not yet tried live (Task 7)
- Task 6: minor (deferred): default-branch detection via `git symbolic-ref refs/remotes/origin/HEAD` fails when origin/HEAD unset and returns an `origin/`-prefixed name (gh fallback named but not mandated)
- Final: minor (deferred): GhCli null-head GitHubException message rewritten to generic "unexpected output" by the surrounding catch; status --refresh repeats warnings per invocation (one-shot, acceptable)

## Acceptance (Task 7)

- Task 7: complete (acceptance: /noir-spec → seal → noir run (daemon) → noir-test PR #4, first pass, $0.45; human fix pushed a2acb6c, merged → pr_disposition modified recorded by daemon 26s after merge; human time 3m 18s; noir-test#2 merged → accepted)
- Fixed after the final review with the user's approval: percentages computed from integer counts (89dbd00).
