---
name: noir-spec
description: Use when the user wants noir (the dark software factory) to build something in the current repository. Co-writes a testable noir spec with the user (intent, API stubs, numbered requirements with concrete examples), checks it, and publishes it as a draft GitHub issue for human approval. A human adds the noir:ready label; the noir server then seals and builds it.
---

# /noir-spec: write a noir spec and publish it as a draft issue

noir turns an **approved spec** into a pull request. A noir **server** watches the repo; when a
human adds the `noir:ready` label to an issue, the server parses the issue body as the spec,
seals it and builds it. This skill is the only interactive step and runs on the user's desktop.
The spec you write together is everything noir will ever know about the work:

- A **builder** agent implements it from the spec and the API stubs.
- A separate **Examiner** agent, which sees *only* the spec and stubs (never this conversation,
  never the repository's code), writes hidden **holdout tests** that decide whether the build is
  accepted.
- **Fairness rule:** the Examiner may test only behavior the spec states. Anything that matters
  and is not written down as a requirement, an example, or stub Javadoc will not be tested, and
  the builder is free to do it differently. Anything vague will be tested by guesswork.

Your job is to get the user's intent into a spec that is complete, precise, and testable, then
publish it as an issue labelled `noir:draft`. The user approves it on GitHub.

## Ground rules

- **Never write into the repository working tree.** Draft the body in a temporary directory
  outside the repo. Read the repository freely; do not create, edit, or delete files in it, and
  do not commit, branch, or push.
- **Never add the `noir:ready` label.** That label is the human approval, and only the human
  adds it. You publish drafts (`noir:draft`) and nothing more.
- **Never run the server commands (the noir run and audit subcommands), and never read anything under `~/.noir`.** The server
  owns that state, and the holdout tests are hidden from you as much as from the builder.
- **This skill never needs `ANTHROPIC_API_KEY`** or any other credential, and never asks the
  user to paste one. All it uses is `gh`, which is already authenticated.
- **Ask one question at a time.** Wait for the answer before moving on. Offer a concrete proposal
  the user can accept or correct rather than an open-ended question whenever you can.
- **Resolve the launcher first.** If `command -v noir` finds it, use `noir`. Otherwise ask the
  user for the path to `bin/noir` in their noir checkout (it runs `target/noir.jar`, built with
  `mvn -B package -DskipTests` in that checkout) and use that path wherever this skill says `noir`.

## 1. Preconditions: stop if any fails

Run these from inside the repository and tell the user plainly which one failed.

1. **A git clone of a GitHub repo.** `git rev-parse --show-toplevel` succeeds and
   `git remote get-url origin` is a GitHub remote. Derive `<repo>` (`owner/name`) from it.
2. **`gh` is authenticated.** `gh auth status` succeeds.
3. **A noir server watches the repo.** Run
   `gh label list --repo <repo> --search noir:ready --json name -q '.[].name'` and require the
   exact line `noir:ready` in its output; then run the same command with `--search noir:draft`
   and require the exact line `noir:draft`. If either is missing, stop and say: "No noir server
   watches <repo> (its noir labels are missing)."

Read the code on the default branch as the build will see it. If the local checkout is on another
branch or is behind origin, mention it, but do not switch branches or pull for the user.

## 2. Revision or new spec?

If the user invoked `/noir-spec #N` (or says "revise issue N"), go to **section 6**. Otherwise
write a new spec: continue with section 3.

## 3. Read the repository

Before asking anything substantive, build a picture of the code the work will live in:

- the packages under `src/main/java` and the types near where the new work belongs;
- existing public APIs, naming, how errors are reported (which exception types), nullability
  conventions, and whether types are classes, records, interfaces, or builders;
- the test style under `src/test/java` (for your own understanding: the holdout tests are
  JUnit Jupiter only and do not use the repository's test dependencies);
- dependencies in `pom.xml`, and formatting plugins (such as Spotless or Checkstyle) that run
  during `mvn compile`; the stubs must pass them.

noir builds single-module Maven projects on Java 21; if the repo is anything else, say so and stop.
Keep your summary brief and relevant to the user's request.

## 4. The conversation

First create a scratch directory outside the repo with `mktemp -d` (run it once). Shell state
does not carry across separate commands, so record the absolute path it prints and use that
literal path in every later command and in Edit/Write tool calls. Below, `<tmpdir>` stands for
that recorded path: always substitute it. Get the skeleton for the body with a provisional title taken
from the user's first description of the work (ask for one sentence first if they have not
described it yet):

```
noir spec template --title "<title>" > <tmpdir>/body.md
```

The skeleton shows the format. The spec is the issue body, and the issue title is the spec title:

- `## Intent`: a short paragraph, the problem and the outcome, not the implementation.
- `## API contract`: one `### <path>` heading per stub (a repository-relative path under
  `src/main/java/`), each followed by a fenced `java` block holding the complete stub source (or, for a `(modify)` entry, a fragment; see 4d).
- `## Requirements`: `### R1`, `### R2`, ... each with a one-line statement and one or more
  bullets of the form `- **Given** ... **when** ... **then** ...`.
- `## Constraints`: bullets, or `_None._`.
- `## Quality bar`: leave the three generated bullets alone unless the user asks to change them.
- Optional trailing `---` and a `_noir ..._` footer: leave it as generated.

Work through these in order, one question at a time, editing `<tmpdir>/body.md` as agreement forms.

### 4a. Intent

Ask what they want built and why. Restate it as a short intent paragraph and a title (an
imperative phrase such as "Add a bounded LRU cache"). Get agreement before going further. If the
request is really several independent pieces of work, propose splitting it into separate specs.

### 4b. API surface

Propose the public types and method signatures, following the repository's conventions. These
become the **API stubs**. Design them so the hidden tests can exercise the behavior:

- **Holdout tests may import only `java.*`, JUnit Jupiter, and the stubs' packages.** Every type
  a test needs to construct, call, or catch must be a JDK type or a type declared in a stub (or
  already living in a stub's package). Do not expose repository types from other packages in
  the API surface.
- **The Examiner never sees the repository.** If a signature mentions an existing type, the
  Examiner knows only what the spec says about it. Prefer new types plus JDK types.
- **New files or changes to existing files.** A stub for a new file must not exist on the
  default branch; to change an existing class, use a modify entry as described in "Changing
  existing code" below.
- **Behavior lives in methods that throw in the stub.** Every holdout test must fail against the
  stubs, so behavior cannot hide in code the stub already implements (record accessors, enum
  constants, constants, default methods with real bodies). Constructors that validate their
  arguments throw in the stub too. Method and constructor bodies do only
  `throw new UnsupportedOperationException();`.
- **Javadoc is part of the spec.** Put Javadoc on every public type and member stating the
  behavior consistent with the requirements: parameters, return values, `@throws` with exact
  exception types, and `null` handling where decided. The Examiner may test what it states.
- **Keep it deterministic.** Holdout tests cannot read the clock, use unseeded randomness, start
  threads, use the network, or touch files outside a temporary directory, and each test has a
  10 second timeout by default. If behavior depends on time or randomness, make it injectable (take a
  `java.time.Clock`, a seeded `java.util.random.RandomGenerator`, or a `Path`) so it can be
  tested. Concurrency guarantees cannot be tested by the holdout suite; record them as
  constraints if they matter.

Each stub must declare the package that matches its path, compile against the repository, and
follow the repository's formatting rules.

### 4c. Requirements and examples

Agree on numbered requirements `R1`, `R2`, ... in order. Each requirement:

- states **one observable behavior** in terms of the stub API ("`get(key)` returns the value
  most recently stored under `key`"), not implementation details;
- has **at least one concrete example**, written as given / when / then with real values and
  Java expressions, for example given "a `Counter` created with `new Counter(5)`", when
  "`increment()` is called twice", then "`value()` returns `7`". Add examples for boundaries
  and error cases (empty, zero, maximum, duplicate, missing).

**Push back on untestable language.** Do not accept "handles errors gracefully", "is fast",
"is robust", "works correctly", "validates input", "etc.", or "as appropriate". Turn each into a
checkable statement and propose the replacement, for example "handles errors gracefully" becomes
"throws `IllegalArgumentException` when `key` is blank".

**Decide the edges explicitly.** For each method ask what happens with `null`, empty, blank,
negative, out of range, duplicate, and missing inputs where they apply. The Examiner tests `null`
handling, exception types, exception messages, ordering, mutability of returned collections, and
`equals`/`hashCode`/`toString` **only if the spec states them**. If the user cares, write it
down: state the exact exception type, and the exact message only if it matters. If the user does
not care, leave it unstated so the builder has freedom.

Also ask about **constraints**: rules the builder must respect that are not behavioral tests
(for example "no new dependencies", "keep the public API of `Foo` unchanged", "use only the JDK").

Before publishing, read the whole spec back to the user in plain language and resolve any
contradictions or gaps.

### 4d. Changing existing code

Use this when the work changes a Java file that already exists on the default branch. Everything
else stays a new-file stub. A changed file is declared with a heading `### <path> (modify)` under
`## API contract`; noir then locks the build so that only the files the spec lists (the modify
targets and the tests under `## Tests to update`) plus new files may change.

1. Find the default branch (`gh repo view <repo> --json defaultBranchRef -q
   .defaultBranchRef.name`), run `git fetch origin <default-branch>` once, and read each target
   with `git show origin/<default-branch>:<path>`.
2. Under `### <path> (modify)` write a **fragment**: the same `package` line and the enclosing
   type, holding only the new and changed members (not the whole file), each with Javadoc and a
   body that is only `throw new UnsupportedOperationException();`. Unchanged members are left out
   and stay as they are.
3. Write fragments that seal. A **changed member** keeps its exact current signature (name,
   parameter types, return type, modifiers, type parameters, throws) and only gets new Javadoc; a
   different parameter list adds an overload instead. Members cannot be removed or renamed. A
   changed member's current body stays in the stub, so each requirement on it must describe
   behaviour that differs from what the code does today. Fragments contain only methods and
   constructors, keep the same type kind and modifiers, and add the imports their signatures need.
4. Search `src/test/java` with grep for the names of the changed members and for the type itself,
   and **ask** the user which of the files you found belong under `## Tests to update` (a
   section between `## Requirements` and `## Constraints`, one backticked path per bullet, such as
   ``- `src/test/java/com/example/FooTest.java` ``). Never guess: show what grep found and let the
   user decide; leave the section out if they list none.
5. Check against the real code until clean:

```
noir spec check <tmpdir>/body.md --title "<title>" --repo .
```

## 5. Check, approve, publish

Run the mechanical check with the agreed title and fix every problem it reports (one per line),
repeating until it prints `OK`:

```
noir spec check <tmpdir>/body.md --title "<title>"
```

If the spec has modify entries or tests to update, add `--repo .` so noir checks them against the
default branch (see section 4d); `--repo .` needs `origin/HEAD`, and if the check says it is not
set, run `git remote set-head origin --auto` and check again. The rule is:
new-file stubs must not exist; modify targets must exist.
For the new-file stubs, confirm that by hand as well. Find the default
branch with `gh repo view <repo> --json defaultBranchRef -q .defaultBranchRef.name`, run
`git fetch origin <default-branch>` once, and for each stub without `(modify)` run:

```
git cat-file -e origin/<default-branch>:<path>
```

It succeeds when the file exists. If a new-file stub path exists, noir would reject the spec:
either make that entry a `(modify)` entry with the user's agreement, or revise the API so the
path becomes a new class, and check again.

Show the user the exact title and the full body and ask them to approve **that exact text**.
Make any changes they ask for, re-check, and show it again. Do not publish without explicit
approval of the current text. Then publish it as a draft:

```
gh issue create --repo <repo> --title "<title>" --body-file <tmpdir>/body.md --label noir:draft
```

Print the issue URL it returns, delete the scratch directory (`rm -rf <tmpdir>`), and go to
section 7.

## 6. Revising an existing draft (`/noir-spec #N`)

1. Fetch its state: `gh issue view N --repo <repo> --json state,labels,title`. Refuse if the
   issue is closed, lacks `noir:draft`, or has any of `noir:sealing`, `noir:building`,
   `noir:pr-open`, `noir:failed` or `noir:blocked`. Tell the user why: noir is already working on
   it or has finished, so changing the spec now means closing the issue and opening a new one.
   Also refuse if it has `noir:ready`: the server may seal the edited text immediately, which
   would defeat the approval. Tell the user to remove the `noir:ready` label on GitHub first,
   then re-run `/noir-spec #N`.
2. Create the scratch directory with `mktemp -d` (once) and record its absolute path as
   `<tmpdir>` (substitute the literal path everywhere). Save the current body with
   `gh issue view N --repo <repo> --json body -q .body > <tmpdir>/original.md`, copy it to
   `<tmpdir>/body.md`, and work on `body.md`. Keep the title. If a seal was rejected, noir left a
   comment on the issue (`gh issue view N --repo <repo> --comments`): read it and address its
   problems.
3. Revise with the user, one question at a time, using the guidance in section 4. If a noir
   comment says `conflicts_with_existing_tests`, add the tests it names under
   `## Tests to update` once the user agrees. Run `git fetch origin <default-branch>` again before
   checking with `--repo .`. Check with
   `noir spec check <tmpdir>/body.md --title "<title>"` until `OK` (add `--repo .` when the
   spec modifies files), then confirm every new-file stub path is new with the `git cat-file -e`
   check from section 5.
4. Show the user a diff of `original.md` against `body.md`, and on explicit approval run
   `gh issue edit N --repo <repo> --body-file <tmpdir>/body.md` (add `--title "<title>"` if the
   title changed). Delete the scratch directory (`rm -rf <tmpdir>`).

## 7. Finish

End every run with: "Review issue #N on GitHub and add the `noir:ready` label when you're happy
with it. noir will seal and build it from there." After a revision, add that the user re-adds
`noir:ready` themselves (they removed it before the revision); you never do.
