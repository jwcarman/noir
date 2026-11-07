# noir benchmark: five items of increasing size (noir-bookmarks)

Date: 2026-11-07 (run 23:23–00:26 UTC, unattended)
Setup: noir `main` at bfa300f, hidden tests off (the default), builder `claude-opus-5-5`. Specs written by
Claude with `/noir-spec`, approved and merged by Claude under delegation. Each item started from the previous
item's merged result. Raw notes and spec bodies: issues #22, #24, #26, #28, #30 on jwcarman/noir-bookmarks.

## Results

| # | Item | Size of PR | Attempts | First pass | Cost | Approval → PR | Rework cause |
|---|---|---|---|---|---|---|---|
| 1 | CSV import (#22 → #23) | 11 files, +956 | 1 | yes | $1.15 | 8.5 min | — |
| 2 | Notes field in every layer (#24 → #25) | 27 files, +1054/−76 | 2 + 1 (two runs) | no | $3.47 | 11 min (run 2) | contract violation, then a valid spec-problem report |
| 3 | Cursor paging (#26 → #27) | 16 files, +940/−18 | 1 | yes | $1.12 | 8 min | — |
| 4 | Collections, a new aggregate (#28 → #29) | 19 files, +1330 | 2 | no | $1.32 | 9 min | contract violation |
| 5 | Refactor into `TagService` (#30 → #31) | 9 files, +427/−63 | 1 | yes | $0.48 | 3 min | — |

Total: $7.54, five merged PRs, about 4,700 lines. Every delivered PR measured 100% line coverage and 100% mutation
score, with max CRAP between 3 and 9. Final `main` builds with 439 tests, all passing.

## Findings

### 1. The builder is not the bottleneck at this size

All five PRs were correct and idiomatic, and I merged each one after reading the whole main-code diff. No build
failure, quality shortfall or behaviour failure occurred in any attempt. My reviews found only minor issues:

- #23: a literal BOM character in source; import is O(rows × store) through the default `findByUrl`.
- #25: the 2,000-character notes cut can split a surrogate pair (the spec's own wording).
- #27: one test asserts a null `next` with `doesNotExist()`.
- #29: `CollectionService` is not `@Transactional` (a lost update on concurrent adds), and collection names have
  no unique constraint in the database; a view makes N `findById` calls.
- #31: `TagController` holds two lambdas so it can serve both constructors (see finding 2).

The builder also handled things the specs did not spell out:

- It avoided a trap the spec left unstated in #29: 9 locked `@DataJpaTest` tests import only
  `JpaBookmarkRepository`, and the builder cleaned up memberships through the `EntityManager` alone.
- It kept existing tests' meaning. The edits in #25, #27 and #31 were minimal, for example renaming
  `sixFieldsIsWrongFieldCount` to `seven…` once six fields became valid.

### 2. The bottleneck is noir's contract language

Every rework came from one rule: **a build must keep every public member of the stubs and of each modify
target's base file** (`ApiConformance`, `javap -public`). The rule exists to protect existing callers. At this
size, it turned ordinary changes into traps:

1. **A record can't gain a component.** The old canonical constructor is public API, so it must survive (#24 run 1:
   `missing: public Bookmark(Long, String, String, List, Instant)`). Fragments ignore record headers.
2. **An enum can't gain a constant through a fragment.** `spec check` rejects the fragment. The spec had to be
   redesigned to avoid a new `SkipReason`.
3. **A class stub with no constructor makes the implicit public no-arg constructor binding** (#28 attempt 1:
   `missing: public JpaCollectionRepository()`). This catches anyone who writes an injectable class stub.
4. **Nothing public can be removed or renamed.** A breaking HTTP change had to leave the old controller method in
   place, unmapped (#27). A refactor had to keep `TagController(BookmarkService)` next to the new constructor (#31).

Traps 1 and 3 cost two wasted attempts ($2.93) and one spec revision. `spec check` flagged none of them. Trap 4
makes real refactors, such as moving a method or changing a signature, impossible to express.

### 3. The safety features worked live

- **Spec-problem report (#24).** After the contract violation, the builder did not work around the contradiction.
  In 3 turns ($0.15) it reported it, naming the constraint and the same problem on three request and response
  records that I had missed. The issue went back to draft. After the revision and re-approval, the fresh run
  passed on its first attempt.
- **Feedback loop.** Fixing a contract violation took 40 seconds and $0.20 (#28 attempt 2).
- **Outcome lock.** Locked tests not listed under `## Tests to update` were exercised every run and passed:
  `NotesEndToEndTest` through the paging change, and 9 `@DataJpaTest` classes through the collections change.
  The lock produced no false positives.

### 4. The quality bar says little about wide, shallow changes

The 27-file notes change produced only 20 mutants, and the refactor only 17. For plumbing, the existing locked
tests, plus my review, carried the assurance; mutation score did not.

### 5. Side finding: the reconciler makes redundant label writes

Every poll, for every `DRAFT` item, the reconciler runs `gh issue edit` even when the snapshot it just fetched
already has the right labels. A GitHub 5xx on one of these writes produced a spurious `daemon_error`
(noir-test#23, 23:09 UTC).

## Recommendations, in order

1. **Make API changes expressible.** This is the highest value, since it is the only thing that failed. Options:
   - let a fragment's record header and enum constants splice into the base file;
   - add a `## Removed API` section listing signatures the build may drop, with callers checked by the build;
   - or both.
   Without it, noir can't do refactors.
2. **`spec check` warns about an implicit constructor** when a class stub declares none, naming the constructor
   that will be required. This is cheap, and it prevents trap 3.
3. **Teach the `/noir-spec` skill these rules:** records, enums, implicit constructors, and no removals until
   recommendation 1 lands.
4. **The quick-fix batch from the overnight findings, plus the reconciler label check.**
5. **Then throughput.** Builder quality held at every size tried, so running items in parallel is now worth it.
