# noir: changing existing code (design)

Date: 2026-11-05
Status: draft for review
Builds on: `2026-11-03-noir-v1-design.md` (v1), `2026-11-05-noir-approval-flow-design.md` (Plan 5)

## 1. Problem

noir can only add new files. Every spec needs at least one API stub. A stub whose path already
exists on the default branch is rejected (`stub_exists`). The Examiner sees only the spec, never
the repository. The seal checks run the base commit with the stubs added. The constraint "do not
modify `Counter`" is text the builder may ignore, because nothing enforces it.

So noir cannot extend `Slugifier.slugify` with a `maxLength`, fix a bug in `Csv.parse`, or make
`LruCache` count hits. Most maintenance is that kind of change.

**Goal.** A spec can extend existing classes and change what existing methods do, with the same
guarantees as today. The hidden suite is written and committed before the build and is never
shown to the builder. The approval flow of Plan 5 is unchanged. Java and Maven only.

**In scope:**
- adding members (methods, constructors, overloads) to existing top-level types;
- changing the behavior of existing members.

**Out of scope:**
- removing or renaming members, and changing an existing member's signature (§9).

## 2. Policy

| Question | Decision |
|---|---|
| What the issue shows for an existing file | Only the new and changed members (a *fragment*); the server splices it into the file from the base commit |
| Existing test files | Locked, except files the spec lists under `## Tests to update` |
| Existing production files | Locked, except the files the spec modifies; new files are allowed anywhere noir does not already forbid |
| A builder edits a locked file | `CONTRACT_VIOLATION`: rework with feedback naming the files |

## 3. Issue format

### 3.1 API contract entries

A **new file** works as today. Its heading is `### <path>`, followed by the full stub, and the path
must not exist at the base commit.

A **modified file** has the heading `### <path> (modify)` and a ```` ```java ```` fence holding a
fragment:

```java
package org.jwcarman.noirtest;

import java.util.Optional;

public final class Csv {

  /** New overload: ... */
  public static List<List<String>> parse(String text, char separator) {
    throw new UnsupportedOperationException();
  }

  /** Changed: a lone '\r' is now a line break. ... */
  public static List<List<String>> parse(String text) {
    throw new UnsupportedOperationException();
  }
}
```

A fragment is a compilation unit with:
- the target file's package;
- the imports the new signatures need;
- exactly one top-level type, with the same name and kind (class, interface, enum or record) as a
  top-level type in the target file, and the same modifiers.

Its body contains only methods and constructors. Each member's body must be exactly `throw new
UnsupportedOperationException();`. Fields, initializers and nested types are not allowed in a
fragment.

### 3.2 Tests to update

The optional `## Tests to update` section comes between `## Requirements` and `## Constraints`. It
is a bullet list of repository-relative paths under `src/test/java/`, each in backticks. A spec
with no such files omits the section. The section order is now Intent, API contract, Requirements,
Tests to update (optional), Constraints, Quality bar.

### 3.3 Round trip

`parse(render(spec)) == spec` still holds. `SpecRenderer` writes `(modify)` headings and the new
section. A spec with no modified files and no tests to update renders exactly as today.

## 4. Splicing

`StubSplicer.splice(String baseSource, String fragment) -> Spliced` uses JavaParser, which noir
already depends on. The result is the full file plus the lists of added and changed member
signatures.

For each fragment member, the *signature* is the name plus the parameter types as written, with whitespace removed.
- **Changed member:** the target type has a member with the same signature. Its Javadoc is
  replaced by the fragment's. Its **body is kept as it is at the base commit**. Its return type,
  modifiers, type parameters and `throws` clause must equal the fragment's, or the splice fails.
- **Added member:** there is no such member. It is appended to the end of the target type, with
  the fragment's Javadoc and the UOE body.
- **Imports:** the fragment's imports are added to the file's imports if absent.
- **Everything else:** all other content of the file is unchanged. That includes comments,
  members, other types and their order.

The splice **fails** with a message and a fix hint for:
- a missing target type, or a mismatched kind or modifiers;
- a changed member with a different return type, modifiers, type parameters or `throws` clause;
- a fragment member whose body is not the UOE throw;
- fields, initializers or nested types in the fragment;
- two fragment members with the same signature;
- a fragment or base file that does not parse.

Keeping changed bodies is deliberate. The seal check "every hidden test fails against the stubs"
then means "every hidden test fails against today's behavior", which proves that a bug-fix test
targets the bug.

## 5. Sealing

`ApprovalSealer`, after the lock check and parse (Plan 5 §6), checks the following:
- Each new-file stub's path must not exist at the base commit (`stub_exists`, as today).
- Each modify target must exist at the base commit and must splice cleanly. Each listed test path
  must exist at the base commit. Any failure here rejects with `invalid_spec`, and the problem
  names the path and the splice message.
- The spliced files become the item's stubs (`stubs/<path>`), and new-file stubs stay as written.
  From here on, every consumer of stubs sees complete files: the seal checks, API conformance, the
  Examiner and SPEC.md.

**The Examiner** receives the spec as today, with fragments shown under their `(modify)` headings.
It also receives a new `## Current source` section holding each spliced file in full. It still sees
no other repository file and never sees the builder's work. Its reference implementation is a
complete file at every stub path, including modified ones. The Examiner prompt gains the modify
rules: keep every public member, and change only what the requirements say.

**The seal checks** are today's checks, with the stubs and the reference overlaid on the base
repository:
1. The hidden tests fail with the stubs in place. For a changed member, that means against today's
   behavior.
2. The hidden tests pass with the reference in place, three times.
3. PIT on the reference scores at least 0.70.
4. **New:** the repository's existing tests pass with the reference in place. Test classes in
   files listed under `## Tests to update` are excluded. A failure becomes an Examiner problem
   naming the test class and method, and is fed back as usual.

If the final Examiner attempt ends with only check-4 problems, the seal is rejected with a **new
reason**, `conflicts_with_existing_tests`. The issue comment names the failing test classes. They
are ordinary repository code, so naming them reveals nothing secret. The comment suggests listing
them under `## Tests to update`. Otherwise rejections are as in Plan 5: comment, remove
`noir:ready`, and move to DRAFT.

**The commitment format is unchanged.** The spliced stubs are a deterministic function of the
issue body, which `bodySha256` covers, and the base commit, which `baseSha` covers.

## 6. Building, measuring and delivery

**The builder** still works on the whole repository. SPEC.md gains a **"Files you may change"**
list: the modify targets, the tests to update, and "any new file". The builder prompt states the
lock rule.

**The lock check** is new and runs after each build attempt, before measurement, for every
spec, including new-file-only ones. That finally enforces constraints like "do not modify
`Counter`". On the host,
`LockCheck` compares the attempt bundle with the base commit by name and status, using the
existing bundle tooling and never running git in the builder's tree. Every path that existed at
the base commit is locked unless it is a modify target or a listed test. A locked path that was
modified, deleted or renamed is a violation. This covers `pom.xml`, resources and any other file.
Violations are a `CONTRACT_VIOLATION` with feedback like this:

> You modified `src/test/java/.../CsvTest.java` and deleted `src/main/java/.../Counter.java`. This
> spec allows changes only to: `src/main/java/.../Csv.java`, and new files. Restore those files and
> make the change another way.

The feedback names only repository paths. New files are allowed except where noir already forbids
them (the holdout package).

**Measurement:**
- `mvn verify` runs every test, so the locked existing tests are the regression gate, as today.
- API conformance covers modify targets too: each public member of the spliced stub must exist in
  the built class with the same signature.
- In modify targets, line coverage, mutation score and CRAP count only the **lines the attempt
  added or changed**. Those are the diff hunks of the attempt against the base commit, mapped to
  JaCoCo lines and PIT mutation lines, and to the methods containing them for CRAP. New files count
  in full, as today. A modify target with no changed lines adds nothing to the metrics.

**Grading** is unchanged.

**The PR body** gains a `### Files` section with the file list and line counts:

```
### Files

- Added: `src/main/java/.../CsvDialect.java` (+40)
- Modified: `src/main/java/.../Csv.java` (+12 −3)
- Tests updated: `src/test/java/.../CsvTest.java` (+2 −2)
```

## 7. Desktop

`noir spec check <file> --title <t> [--repo <dir>]`. With `--repo`, it finds the default branch of
`<dir>`'s `origin`, splices against `origin/<default>`, checks that the modify targets and listed
tests exist, and checks that new-file stubs do not exist. Without `--repo`, it parses and lints
only, as today. `--issue` works the same way.

**The `/noir-spec` skill:**
- When the request changes existing code, it reads each target from `origin/<default>`.
- It drafts a fragment with only the new and changed members, showing changed members with their
  current signature and new Javadoc.
- It searches `src/test/java` for references to the changed members. It asks the user which of
  those files belong under `## Tests to update`, and never guesses.
- It runs `noir spec check --repo .` until clean.

The desktop still needs no credential and no Docker.

## 8. Migration

- **`spec.json` schema version 2** adds `modifies` (the `apiStubs` paths that are modify targets)
  and `testsToUpdate`. Both default to empty, and version 1 files read with both empty.
- **New-file-only specs** parse, render and seal exactly as today. The only change to how they
  are built is the lock check (§6).
- `SealRejection` gains `CONFLICTS_WITH_EXISTING_TESTS` (`conflicts_with_existing_tests`).
- **No new metrics.** Lock-check failures count under the existing `CONTRACT_VIOLATION` category.
- **The README and the skill** document modify specs.

## 9. Out of scope

- Removing or renaming members, and changing an existing member's signature, modifiers or `throws`
  clause.
- Fields, initializers and nested types in fragments.
- Modifying non-Java files (`pom.xml`, resources).
- Multi-module projects.
- Automatic rebase when `main` moves after sealing. GitHub shows the conflict and a human resolves
  it.
- The Examiner seeing source files other than the modify targets.

## 10. Testing

**Unit tests:**
- `StubSplicer`:
  - added members, changed members, Javadoc replacement, kept bodies and import merging;
  - comments and order preserved;
  - every failure in §4.
- `IssueSpec`: `(modify)` headings, `## Tests to update` (present, absent, malformed, out of
  order), and the round trip.
- `SpecJson` v1 and v2 reads.
- `LockCheck`: modified, deleted and renamed locked files; allowed modify targets and listed tests;
  new files; the holdout package; the feedback text.
- The changed-line filter for coverage, PIT and CRAP.
- Seal check 4 and the `conflicts_with_existing_tests` rejection.
- `noir spec check --repo`.

**New `PipelineIT` scenarios:**
- **Modify:** a bug fix to an existing class, with a listed test update, ending in `SEALING > SEALED
  > BUILDING > MEASURING > GRADING > PR_OPEN`.
- **Locked edit:** a builder that edits a locked test goes to `REWORK(CONTRACT_VIOLATION)`, then
  passes on the next attempt.
- **Conflict:** a spec that contradicts a locked test ends in `SEALING > DRAFT` with
  `conflicts_with_existing_tests`.

**Acceptance on noir-test:**
1. An extension: `Slugifier.slugify(title, maxLength)`.
2. A behavior change: `Csv.parse` treats a lone `\r` as a line break, with `CsvTest` listed under
   `## Tests to update`.
3. A deliberately conflicting spec, which must be rejected with `conflicts_with_existing_tests`.
