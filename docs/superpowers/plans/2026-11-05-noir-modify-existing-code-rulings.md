# Plan 6 (changing existing code) — rulings and deferred minors

Collected from the SDD ledger (2026-11-06). Each ruling: what was decided — why — cost if wrong.

## Rulings

- Pre: Ruling: Task 3 adds `fileChanges` to every Bundles implementation, including FactoryTest.FakeBundles (returning an empty list or recorded changes); Task 6 makes the fake configurable — the interface must compile after Task 3 — cost if wrong: none
- Task 2: Ruling: bodyless fragment members (abstract interface methods) stay rejected per spec §3.1 — adding an abstract method to an interface breaks implementers and is out of v1 scope; changing an interface method's Javadoc only is rare — cost if wrong: such specs get a clear invalid_spec message
- Task 2: Ruling: a matched fragment member without Javadoc keeps the base Javadoc (still "changed") — nothing to replace it with — cost if wrong: none
- Task 2: Ruling: signature = parameter types as written (plan Review Focus 3) over spec §4's "erased" — javac forbids same-erasure overloads so both agree on compilable files; amend spec wording; the test pinning take(List<String>) + take(List<Integer>) in one file must be rewritten to compilable inputs — cost if wrong: none
- Task 2: Ruling: fix round 1 also takes Minor 4 (document ignored type params/extends/implements in class Javadoc) and Minor 6 (name compact constructors in the message); Minors 5,7,8 deferred — cost if wrong: none
- Task 3: Ruling: move FileChange to package deliver (brief named factory/) — avoids a new deliver→factory cycle; Bundles in factory imports it — cost if wrong: one import change
- Task 3: Ruling: fix round 1 also takes: rename+modify test, drop unreachable 'C' case, tidy inline FQNs; UTF-8 lossiness and directory/'-'-rev inputs deferred (inputs are noir-controlled paths/SHAs)
- Task 4: Ruling: "## Files you may change" goes just before SPEC.md's closing injection guard (brief said "ends with") — the guard must stay last — cost if wrong: none
- Task 4: Ruling: SPEC.md shows fragments (brief) though spec §5 lists SPEC.md among complete-file consumers — the builder sees the full repo, and the fragment shows exactly what changes — cost if wrong: none
- Task 5: Ruling: test-deps prepare step runs only when EXISTING_TESTS=yes — keeps new-file seals as fast as before — cost if wrong: none
- Task 5: Ruling: accept test-deps as `mvn test -Dtest=*#noirResolveProvidersOnly -Dsurefire.failIfNoSpecifiedTests=false` instead of -DskipTests — -DskipTests does not resolve surefire's JUnit provider, so the offline run failed (verified by the implementer's IT) — cost if wrong: a slower prepare step
- Task 5: Ruling: fix round 1 = restore src/test from BASE_SHA before the existing-tests run (+ IT with a reference that contains a test file); also Minor 2 (neutral wording "existing tests could not run with the reference" when test-deps failed or no reports) and Minor 4 (surefire forkedProcessTimeoutInSeconds bound); Minor 3 (flaky/already-failing baseline) deferred — listing the test is the workaround — cost if wrong: a misleading conflict comment on a flaky repo
- Task 6: Ruling: I1 fix (a) — align GitFixtures.POM and builder fixture poms so no builder but pom-cheat touches pom.xml; drop the modifies overload — cost if wrong: none
- Task 6: Ruling: I2 — allowed means "may modify", not "may remove": DELETED (and renamed-away) allowed paths are violations — keeps listed tests as regression checks and matches Task 8 — cost if wrong: a spec that wants a test file deleted cannot express it (out of v1 scope)
- Task 6: Ruling: I3 — add a Docker scenario with an added .mvn/maven.config skipping tests, expecting REWORK(QUALITY_SHORTFALL) with lineCoverage 0.0; rename pomCannotSkipTheGrader → pomEditIsRejectedByLockCheck with a correct javadoc; Minor 7 FQNs → imports; Minor 5 deferred
- Task 7: Ruling: C1 — replace replay with a single cumulative diff: new Bundles.diffSince(bundle, baseSha, branch, List<String> paths) running `git diff -U0 --no-color --no-ext-diff --no-textconv base branch -- paths`; DiffLines parses one diff; real-git test with two commits (shift + delete-of-earlier-addition) — removes merge (-m) problem too — cost if wrong: none
- Task 7: Ruling: I2 — ClassQuality flag rendered "<class> (changed lines only)" plus one explanatory sentence; listing uncovered line numbers optional — cost if wrong: none
- Task 9: Ruling: fix round 1 = I1 (shared helpers used by ApprovalSealer and SpecCommand; exact-line tests), I2, and Minors 1–5 (README completeness, SKILL complete-stub line for (modify), reflow, revision refetch + conflict guidance, bad --repo path error), plus Minor 8 test imports; Minor 6 (two default-branch sources) and 7 (GitCli built inline; desktop always needs real git) deferred — cost if wrong: none
- Task 10: Ruling: fix round 1 = I1 + exact ### Files count lines + builder-never-ran assertion in the conflict scenario — cost if wrong: none
- Final: Ruling: C1 re-graded Critical (spec/plan gap) — for modify targets, discrimination counts only mutants on lines the reference added/changed vs the base file (host-side `git diff --no-index -U0` base vs reference → DiffLines.added; new files whole) — cost if wrong: weak suites on modify targets could seal (still gated per changed line)
- Final: Ruling: I1 — lock ADDED files under `.mvn/` and under `src/test/resources/` named `junit-platform.properties` or in `META-INF/services/`; feedback names them like other locked paths — cost if wrong: a builder can't add those config files (none needed)
- Final: Ruling: fix wave also takes Minors 1 (skill wording), 2 (invalidSpec/stubExists comment texts mention --repo / (modify)), 3 (signature key uses simple type names so java.lang.String == String), 4 (### Files lists Deleted and Renamed honestly for draft PRs), 5 (reject reference files outside stub paths in checkSuite) — all cheap, user-facing — cost if wrong: a few lines
- Final: Ruling: residuals (a), (b), (c) are not fixed in this wave (one fix pass per process) — surfaced to the user with a recommendation to apply (b) and (a) before acceptance; each is a few lines plus a test — cost if wrong: CRLF repos and constant-only changes can't seal; one confusing feedback sentence

## Deferred minors

- Task 1: review Approved (sonnet). minor (deferred): modifies not linted as subset of apiStubs; bad modify path reported twice; empty ## Tests to update section accepted silently; no parse+lint test for those; SpecJson.read now rejects versions other than 1|2 (sensible)
- Task 2: minor (deferred): imports inserted above a leading license comment when base has no package/imports; mixed tab/space re-indent; wildcard import not treated as covering a single import
- Task 2: re-review all addressed (sonnet). minor (deferred): String[] vs String... are different keys (a fragment writing the other form adds an uncompilable duplicate); no test that List<Integer> vs List<String> don't match; printer rebuilt per call
- Task 3: minor (deferred): lossy UTF-8 decode of show output; show of a directory path returns a tree listing; rev starting with '-' unguarded
- Task 3: re-review all addressed (sonnet). minor (deferred): inline FQNs (Map.of in GitCli.show, tests); missing-path detection relies on git's English stderr (LC_ALL=C; fails loudly if reworded)
- Task 4: review Approved (opus). minor (deferred): no test that a new-file-only seal leaves no fragments/; cosmetic naming (spliceProblems holds test problems; ArrayList contains)
- Task 5: minor (deferred): no rerun/baseline for flaky or already-failing existing tests; read -ra reads first line of EXCLUDE_TESTS only; empty-array set -u on bash<4.4 (image is bash 5.2)
- Task 5: re-review all addressed (sonnet). minor (deferred): restore_tests comment omits the src/main-only modify-target assumption; pom-set forkedProcessTimeoutInSeconds overrides the bound; timeout untested
- Task 6: minor (deferred): backtick/newline in a path breaks the feedback code span (builder-only text)
- Task 7: minor (deferred): all-deletion-only attempts record coverage 1.0 / maxCrap 0 in measurement data; changed line outside any method has no CRAP; lambda splits method span; several top-level types in one target reported as one row
- Task 7: re-review all addressed (sonnet). minor (deferred): diffSince called (cheaply) for every spec; "[Fatal Error] Content is not allowed in prolog" XML parser noise in test output
- Task 8: review Approved (sonnet). minor (deferred): no draft-PR Files test; no backtick/newline path test; no cap on listed files (GitHub 65,536-char body limit)
- Task 9: minor (deferred): skill uses gh default branch and --repo uses origin/HEAD (can disagree after a rename); SpecCommand builds GitCli inline instead of an Operations seam
- Task 9: re-review all addressed (sonnet). minor (deferred): RepoProblems.stubExists Javadoc claims server sharing (server uses ApprovalComments.stubExists, different text); --repo on a repo subdirectory rejected; SKILL.md:94 still long
- Task 10: re-review all addressed (sonnet). minor (deferred): decrement() duplicated across six fixture trees; mutationScore==1.0 assertion depends on PIT mutator set
- Final: minor (deferred): SKILL.md:253 over wrap width; diffNoIndex GitException propagates like other infra errors
