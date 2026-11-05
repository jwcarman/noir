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
   exact line `noir:ready` in its output. If it is missing, stop and say: "No noir server watches
   <repo> (its noir labels are missing)."

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

First create a scratch directory outside the repo and keep its path in a shell variable for the
later steps: `tmp=$(mktemp -d)`. Get the skeleton for the body with a provisional title taken
from the user's first description of the work (ask for one sentence first if they have not
described it yet):

```
noir spec template --title "<title>" > "$tmp/body.md"
```

The skeleton shows the format. The spec is the issue body, and the issue title is the spec title:

- `## Intent`: a short paragraph, the problem and the outcome, not the implementation.
- `## API contract`: one `### <path>` heading per stub (a repository-relative path under
  `src/main/java/`), each followed by a fenced `java` block holding the complete stub source.
- `## Requirements`: `### R1`, `### R2`, ... each with a one-line statement and one or more
  bullets of the form `- **Given** ... **when** ... **then** ...`.
- `## Constraints`: bullets, or `_None._`.
- `## Quality bar`: leave the three generated bullets alone unless the user asks to change them.
- Optional trailing `---` and a `_noir ..._` footer: leave it as generated.

Work through these in order, one question at a time, editing `"$tmp/body.md"` as agreement forms.

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
- **Prefer new files.** A stub is copied over the repository at its path, so a stub for an
  existing file replaces that whole file. If the work truly must extend an existing class, the
  stub has to be that file's complete current content plus the new members, and only the new
  members' bodies throw. Discuss this with the user before choosing it.
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

## 5. Check, approve, publish

Run the mechanical check with the agreed title and fix every problem it reports (one per line),
repeating until it prints `OK`:

```
noir spec check "$tmp/body.md" --title "<title>"
```

Show the user the exact title and the full body and ask them to approve **that exact text**.
Make any changes they ask for, re-check, and show it again. Do not publish without explicit
approval of the current text. Then publish it as a draft:

```
gh issue create --repo <repo> --title "<title>" --body-file "$tmp/body.md" --label noir:draft
```

Print the issue URL it returns, delete the scratch directory (`rm -rf "$tmp"`), and go to
section 7.

## 6. Revising an existing draft (`/noir-spec #N`)

1. Fetch it: `gh issue view N --repo <repo> --json state,labels,title,body`. Refuse unless the
   issue is open and labelled `noir:draft`, and is not labelled `noir:sealing`, `noir:building`
   or `noir:pr-open`. Say plainly why (for example "it is already being built"). If the
   `noir:ready` label is still present, tell the user it will be re-sealed when they re-approve.
2. Create the scratch directory (`tmp=$(mktemp -d)`), write the body to `"$tmp/body.md"`, and
   keep the title. If a seal was rejected, noir left a comment on the issue
   (`gh issue view N --repo <repo> --comments`): read it and address its problems.
3. Revise with the user, one question at a time, using the guidance in section 4. Check with
   `noir spec check "$tmp/body.md" --title "<title>"` until `OK`.
4. Show the user a diff of the old and new text, and on explicit approval run
   `gh issue edit N --repo <repo> --body-file "$tmp/body.md"` (add `--title "<title>"` if the
   title changed). Delete the scratch directory (`rm -rf "$tmp"`).

## 7. Finish

End every run with: "Review issue #N on GitHub and add the `noir:ready` label when you're happy
with it. noir will seal and build it from there." If a previous seal was rejected, remind the
user that after revising they re-add `noir:ready` themselves; you never do.
